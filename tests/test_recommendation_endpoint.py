#!/usr/bin/env python3
"""Source-only endpoint delegation: fake only the exact TCP probe, NO sockets."""
import os,subprocess,tempfile,unittest
from pathlib import Path
SCRIPT=Path(__file__).resolve().parents[1]/'stack.config/mastodon/configure-bootstrap-recommendations.sh'
class RecommendationEndpointTests(unittest.TestCase):
 def test_declared_endpoint_and_fail_closed(self):
  source=SCRIPT.read_text();probe='(echo > /dev/tcp/"$redis_host"/"$redis_port")'
  self.assertEqual(1,source.count(probe))
  self.assertIn('redis_host="${REDIS_HOST:-valkey}"',source)
  self.assertIn('redis_port="${REDIS_PORT:-6379}"',source)
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);script=root/'source-probe-fixture.sh'
   script.write_text(source.replace(probe,'[ "$redis_host:$redis_port" = "$SOURCE_FAKE_REACHABLE_ENDPOINT" ]'))
   (root/'bundle').write_text('#!/bin/sh\n[ "$1 $2 $3" = "exec rails runner" ] || exit 9\ntouch "$CALL_MARKER"\n');(root/'bundle').chmod(0o755)
   (root/'seq').write_text('#!/bin/sh\nprintf "30\\n"\n');(root/'seq').chmod(0o755)
   marker=root/'called';env=dict(os.environ,PATH=f'{root}:{os.environ["PATH"]}',REDIS_HOST='source.invalid',REDIS_PORT='12345',SOURCE_FAKE_REACHABLE_ENDPOINT='source.invalid:12345',CALL_MARKER=str(marker),MASTODON_BOOTSTRAP_FOLLOW_FRAGMENT_DIR=str(root/'empty'))
   result=subprocess.run(['bash',str(script)],env=env,capture_output=True,text=True,timeout=10)
   self.assertEqual(result.returncode,0);self.assertTrue(marker.exists(),'Rails delegation skipped')
   marker.unlink();env['REDIS_PORT']='54321'
   result=subprocess.run(['bash',str(script)],env=env,capture_output=True,text=True,timeout=10)
   self.assertNotEqual(result.returncode,0);self.assertFalse(marker.exists(),'wrong endpoint proceeded')
if __name__=='__main__':unittest.main()
