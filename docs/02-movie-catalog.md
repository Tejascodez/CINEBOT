# Step 2 — Movie Catalog & TMDB Sync

## Requirements
- Public, paginated movie listing with genre/language filters and whitelisted sorting.
- Movie detail; genre list.
- Typo-tolerant title search ("intersteller" → Interstellar).
- Admin-triggered (and optional nightly) import of TMDB "now playing" (region IN).
- **Never call TMDB on a user request**: every read is served from PostgreSQL.

Deferred on purpose: `city` / `nowShowing` filters need shows (Step 3); Redis caching of catalog reads comes with Redis (Step 4).

## Data model (V2)
`genres(tmdb_genre_id UK, name UK)` · `movies(tmdb_id UK, …, popularity NOT NULL)` · `movie_genres(movie_id, genre_id) PK` · `movie_sync_runs`.

| Constraint / index | Purpose |
|---|---|
| `UNIQUE(movies.tmdb_id)` | Natural key of an import → re-sync updates, never duplicates |
| `GIN (lower(title) gin_trgm_ops)` | Serves both `LIKE '%dark kn%'` and the `<%` word-similarity operator |
| `(popularity DESC, id)`, `(release_date DESC, id)` | Default sort orders; `id` tie-breaker keeps paging stable |
| `idx_movie_genres_genre` | "Movies in genre X" (the PK only covers lookups by movie) |
| Partial `UNIQUE ((status)) WHERE status='RUNNING'` on `movie_sync_runs` | At most one sync at a time, across all instances |
| CHECKs on runtime, rating, lowercase language | Bad data rejected by the DB, not just code |

Only image *paths* are stored; URLs are built in `MovieMapper`, so changing image size/CDN needs no migration.

## Sync flow
```
POST /admin/movies/sync
  ├─ TMDB configured?                         no → 503 TMDB_NOT_CONFIGURED
  ├─ recorder.start()  [tx]  mark >30 min RUNNING runs FAILED, insert RUNNING   conflict → 409 SYNC_ALREADY_RUNNING
  ├─ fetch genres      [HTTP, no tx] → writer.upsertGenres [tx]
  ├─ for page 1..N:    fetch now_playing + details per movie [HTTP, no tx]
  │                    (one failed movie → skipped & counted, not fatal)
  │                    writer.upsertMovies(page) [tx per page]
  └─ recorder.finish(SUCCEEDED | FAILED + counters + error)  [tx]      TMDB down → 502 TMDB_UNAVAILABLE
```

## Key decisions
| Decision | Why |
|---|---|
| No transaction around HTTP calls | Holding a DB connection + locks during seconds of network I/O exhausts the pool. Fetch first, then write each page in its own short transaction. |
| Transaction per page, not per sync | A failure on page 3 keeps pages 1–2; safe because every write is an upsert keyed by `tmdb_id`. |
| `MovieSyncRunRecorder` / `MovieCatalogWriter` as separate beans | `@Transactional` works through a Spring proxy; calls between methods of the same class skip the proxy, so the transaction would be silently ignored. |
| DB-enforced single running sync | Works across several instances with no distributed lock; a stale-run sweep stops a crashed instance from blocking syncs forever. |
| `default_batch_fetch_size: 50` instead of `JOIN FETCH` | `JOIN FETCH` on a collection plus pagination makes Hibernate paginate in memory. Batch fetching = 3 queries per page regardless of size (a test asserts it). |
| `EXISTS` subquery for the genre filter | A join would duplicate movie rows and inflate `totalElements`. |
| Sort whitelist enum | Arbitrary `sort=field` allows sorting on unindexed columns and produces 500s on unknown fields. |
| LIKE wildcard escaping | Otherwise `q=%` would match every movie. |
| Read-access token (header) preferred over v3 api_key (query) | Query strings end up in proxy/access logs; error messages include only the path. |
| Dev seed as a repeatable Flyway migration in `db/dev-seed` (dev profile only) | Was: 10 sample movies so the app ran without a TMDB key. Now a no-op (2026-11-09): movies come only from a TMDB sync; the file stays so existing Flyway history resolves. |

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/movies` | public | `genreId, language (ISO 639-1), sort=POPULARITY\|RELEASE_DATE\|RATING\|TITLE, page, size≤50` |
| GET | `/api/v1/movies/search` | public | `q` (2–100 chars), `page, size` |
| GET | `/api/v1/movies/{id}` | public | 404 `MOVIE_NOT_FOUND` |
| GET | `/api/v1/genres` | public | alphabetical |
| POST | `/api/v1/admin/movies/sync` | ADMIN | returns the run; 409 / 502 / 503 as above |
| GET | `/api/v1/admin/movies/sync-runs` | ADMIN | latest 20 runs |

## Tests
- `TmdbClientTest`: JSON mapping, `""` release date, bearer vs api_key auth, 4xx/5xx → `TmdbException` without leaking the key.
- `MovieCatalogIT`: filters, no duplicate rows, paging, sort whitelist, validation, typo/partial/wildcard search, 404/400, **query-count guard** (verified to fail with batch fetching disabled).
- `MovieSyncIT`: import, idempotent re-sync with changed title and genres, duplicate ids on a page, skipped movie, outage → FAILED run + 502, not configured → 503, **real two-thread race → second sync gets 409**, stale-run recovery, admin-only access.
