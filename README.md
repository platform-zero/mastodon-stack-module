# mastodon stack module

- Module id: `mastodon`
- Module repo: `mastodon-stack-module`
- Source repo: none declared
- Lifecycle: `active`

## Owned overlays
- `stack.runtime.yaml`
- `stack.config/mastodon`

## Dependencies
- `stack-foundation`

## Validation

```sh
./tests/validate.sh
```

## Fresh native database ordering

All four schema consumers (web, streaming, Sidekiq and recommendation seeder)
require **completed** `mastodon-init`, not merely a running PostgreSQL server.
The seeder must not run Rails against a fresh database while `db:prepare` is
still in flight. Existing storage, PostgreSQL and Valkey gates remain unchanged;
no dependency cycles, ignored SQL errors, startup sleeps or readiness bypasses.

Validation runs six actual-declaration/negative graph methods and the existing
recommendation endpoint source fixture. These are source regressions, not native
database migration, populated UI, SSO or installed application acceptance.

## Lifecycle

`active` modules are expected to keep `stack.module.json`, owned overlays, and `tests/validate.sh` in sync.
