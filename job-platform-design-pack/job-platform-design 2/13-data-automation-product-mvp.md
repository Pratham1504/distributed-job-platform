# Data Processing and Report Automation Product MVP

## 1. Product definition

The user-facing product is a web application for safely processing business CSV files in the background. The Distributed Job Platform is the reliable execution engine beneath it.

The first complete user journey is:

```text
Create project -> Upload CSV -> Start processing -> Watch live status
-> Review error summary -> Download cleaned CSV and error report
```

The application must not pretend to process a file. A completed job must have actually validated/processed its uploaded CSV and persisted a real result.

## 2. MVP users and problem

An analyst or operations user uploads a CSV containing sales, customer, inventory, or transaction data. Instead of waiting in the browser while the file is processed, they submit a background task, can close the page, and later download validated output.

## 3. MVP scope

### Required pages

- Register/login and project selector
- File-upload page
- Job creation page with validation/cleaning options
- Job list and job-detail progress page
- Result page: total rows, valid rows, rejected rows, duplicates removed, bounded previews of retained/rejected/duplicate rows, and downloadable cleaned file and error report

### Required business flow

1. User uploads a CSV to a project.
2. API validates size, MIME type, extension, ownership, and optional header preview.
3. API stores a `file_asset` and returns `assetId`.
4. User creates a `PROCESS_FILE` job with `sourceAssetId` and an allowed processing profile.
5. Worker reads the source asset, validates rows, normalizes values, detects duplicates, and creates two real output assets:
   - cleaned CSV
   - error CSV containing rejected row number and reason
6. Worker stores a durable result summary and marks job complete.
7. Dashboard displays the summary, bounded retained/rejected/duplicate row samples, and authorised downloads. The full CSV is never rendered in the browser.

### Processing profiles

| Profile | Required behaviour |
| --- | --- |
| `CSV_VALIDATE` | Validate header, row width, UTF-8, empty required fields, and malformed records; produce error CSV |
| `CSV_NORMALIZE` | Includes validation; trim strings, normalize header case, standardize dates to ISO-8601 where recognized, remove exact duplicate rows |

No AI transformation, arbitrary formula, arbitrary upload execution, arbitrary file type, or user-supplied code is in MVP.

## 4. File limits and retention

- CSV only; UTF-8; maximum 10 MiB and 100,000 rows in MVP.
- Reject password-protected, binary, or malformed encoding uploads.
- File uploads and generated artifacts use private object storage in deployed environments; a local filesystem adapter may be used in development.
- Retain source/output/error assets for 30 days, then delete them and preserve only job summary metadata for the remaining job-retention window.

## 5. Product API additions

| Endpoint | Behaviour |
| --- | --- |
| `POST /projects/{projectId}/files` | Multipart CSV upload; returns `assetId`, hash, size, header preview |
| `GET /projects/{projectId}/files/{assetId}` | Returns owned asset metadata |
| `GET /projects/{projectId}/files/{assetId}/download` | Returns short-lived authorised download URL or streamed file |
| `POST /projects/{projectId}/jobs` | `PROCESS_FILE` payload references `sourceAssetId` and profile |

## 6. Data model additions

`file_assets` stores metadata and ownership; object storage stores file bytes. Job payload references `sourceAssetId`, never an arbitrary storage URL.

The terminal result shape for `PROCESS_FILE` is:

```json
{
  "type": "PROCESS_FILE",
  "sourceAssetId": "uuid",
  "cleanedAssetId": "uuid",
  "errorAssetId": "uuid",
  "totalRows": 10000,
  "validRows": 9800,
  "rejectedRows": 120,
  "duplicatesRemoved": 80
}
```

## 7. Acceptance criteria

| ID | Given | When | Then |
| --- | --- | --- | --- |
| DA-01 | authenticated project user | uploads valid CSV within limit | receives project-owned `assetId` and safe header preview |
| DA-02 | uploaded CSV asset | creates `CSV_VALIDATE` job | worker produces actual validation counts and an error CSV |
| DA-03 | uploaded CSV with duplicate rows | creates `CSV_NORMALIZE` job | result reports removed duplicates and produces cleaned CSV |
| DA-04 | user does not own project/asset | creates process job or download request | API returns `404` |
| DA-05 | processing job is retried automatically | worker restarts | output effect remains safe; output assets are not duplicated logically |
| DA-06 | completed job with output assets | user opens job detail | sees summary and can download only their project assets |

## 8. Product implementation order

1. Create `file_assets` migration and local artifact storage adapter.
2. Implement upload, metadata, and authorised download APIs.
3. Implement one real `PROCESS_FILE` handler for `CSV_VALIDATE`.
4. Build job-result page with error-summary/download links.
5. Add `CSV_NORMALIZE` and deduplication.
6. Add report generation/email only after the file workflow is complete.

## 9. Resume description

> Built a full-stack asynchronous CSV processing and report-automation platform using React, Java Spring Boot, PostgreSQL, RabbitMQ, Redis, Docker, and Azure. Implemented secure file uploads, background validation/normalization, downloadable result artifacts, transactional outbox delivery, idempotent retries, worker leases, and monitoring.
