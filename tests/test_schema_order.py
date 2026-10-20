#!/usr/bin/env python3
"""Actual declared schema edges; source-only, NOT native DB/app acceptance."""
from pathlib import Path
import copy
import re
import unittest

RUNTIME = Path(__file__).resolve().parents[1] / 'stack.runtime.yaml'
CONSUMERS = ('mastodon-web', 'mastodon-streaming', 'mastodon-sidekiq', 'mastodon-recommendation-seeder')

def declared_graph():
    # This module's literal two/six-space service/dependency YAML subset only.
    # The full schema/parser is still validated by the generator module contract.
    text = RUNTIME.read_text()
    graph = {}
    for block in re.finditer(r'^  (mastodon[-a-z]*):\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|^networks:|\Z)', text, re.M | re.S):
        name, body = block.groups()
        deps = re.search(r'^    dependencies:\n((?:^      [a-z][a-z0-9-]*: "[a-z]+"\n)+)', body, re.M)
        graph[name] = dict(re.findall(r'^      ([a-z][a-z0-9-]*): "([a-z]+)"$', deps[1], re.M)) if deps else {}
    if set(graph) != {*CONSUMERS, 'mastodon-init', 'mastodon-storage-init'}:
        raise ValueError('Unexpected literal service shape; use full schema review, not silent parser fallback')
    return graph

def check(graph):
    for name in CONSUMERS:
        if graph[name].get('mastodon-init') != 'completed':
            raise ValueError('Schema consumer must wait for completed native initialization: ' + name)
    def visit(name, chain):
        if name in chain:
            raise ValueError('Cyclic native initialization dependency')
        for dependency in graph[name]:
            if dependency in graph:
                visit(dependency, (*chain, name))
    for name in graph:
        visit(name, ())

class SchemaOrderTests(unittest.TestCase):
    def test_actual_all_schema_consumers_wait_completed_init(self):
        check(declared_graph())

    def test_actual_init_does_not_wait_for_consumers(self):
        self.assertFalse(set(declared_graph()['mastodon-init']) & set(CONSUMERS))

    def complete(self):
        graph = copy.deepcopy(declared_graph())
        for name in CONSUMERS:
            graph[name]['mastodon-init'] = 'completed'
        return graph

    def test_missing_seeder_schema_edge_rejected(self):
        graph = self.complete()
        del graph['mastodon-recommendation-seeder']['mastodon-init']
        with self.assertRaises(ValueError):
            check(graph)

    def test_streaming_early_schema_access_rejected(self):
        graph = self.complete()
        del graph['mastodon-streaming']['mastodon-init']
        with self.assertRaises(ValueError):
            check(graph)

    def test_started_not_equivalent_to_completed(self):
        graph = self.complete()
        graph['mastodon-recommendation-seeder']['mastodon-init'] = 'started'
        with self.assertRaises(ValueError):
            check(graph)

    def test_init_consumer_cycle_rejected(self):
        graph = self.complete()
        graph['mastodon-init']['mastodon-recommendation-seeder'] = 'completed'
        with self.assertRaises(ValueError):
            check(graph)

if __name__ == '__main__':
    unittest.main()
