#!/usr/bin/env python3
"""Source-only endpoint delegation regression; not app/SSO acceptance."""
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'stack.config/mastodon/configure-bootstrap-recommendations.sh'

class RecommendationEndpointTests(unittest.TestCase):
    def test_declared_endpoint_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = root / 'bundle'
            bundle.write_text('#!/bin/sh\n[ "$1 $2 $3" = "exec rails runner" ] || exit 9\ntouch "$CALL_MARKER"\n')
            bundle.chmod(0o755)
            seq = root / 'seq'
            seq.write_text('#!/bin/sh\nprintf "30\\n"\n')
            seq.chmod(0o755)
            marker = root / 'called'
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', REDIS_HOST='127.0.0.1', CALL_MARKER=str(marker), MASTODON_BOOTSTRAP_FOLLOW_FRAGMENT_DIR=str(root / 'empty'))
            with socket.socket() as server:
                server.bind(('127.0.0.1', 0))
                server.listen(1)
                env['REDIS_PORT'] = str(server.getsockname()[1])
                result = subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0)
                self.assertTrue(marker.exists(), 'native Rails stage was silently skipped')
            marker.unlink()
            result = subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(marker.exists(), 'unreachable Redis proceeded to native configuration')

if __name__ == '__main__':
    unittest.main()
