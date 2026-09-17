# Durable CACHE calibration input

`cache-policy-history.sqlite` is the canonical static CACHE calibration history,
retained in Git as measured input to the Python tuner. It is not disposable
benchmark output. Raw logs remain under ignored experiment directories.

The database was migrated with SQLite backup from
`experiments/cache-scarce-loop/forks.sqlite`. Every stored value, schema entry,
measurement window, independent fork ID, campaign ID and provenance field was
compared after migration. The source database and session artifacts remain
historical evidence. Do not normalize or rewrite those observations.

Generic parameter-loop tasks may declare `storePath`, resolved against their
configured repository `root`. Without it they retain the legacy
`<output>/forks.sqlite` behavior. Smoke runs always use their separate session
store. Resume records the store location and verifies that a relocated database
contains all prior rows before allowing more measurements. Ordinary transactions
and primary keys make repeated ingestion idempotent and reject conflicting IDs.

Dynamic phase/window measurements are validation artifacts with a distinct
`recordType`; the static store and training panel builders reject them. They
must never become static surrogate response rows. SQLite WAL/SHM sidecars are
not source artifacts.
