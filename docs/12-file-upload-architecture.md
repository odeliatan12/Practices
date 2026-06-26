# 12 — File Upload Architecture

## What Needs Uploading

| Use Case | Uploader | File Type | Max Size |
|---|---|---|---|
| Product images | Admin | JPEG, PNG, WebP | 5 MB |
| Bulk order import | Admin | CSV | 10 MB |
| Bulk inventory import | Admin | CSV | 10 MB |
| Return shipping label (PDF) | Fulfillment Service | PDF | 1 MB |
| Order invoice / receipt | Order Service | PDF | 500 KB |
| Support ticket attachment | Customer | JPEG, PNG, PDF | 5 MB |

---

## Upload Architecture

**Client → API Gateway → Object Storage (S3-compatible)**

Never buffer large files through application servers. Use **presigned URLs** instead:

```
1. Client requests upload URL:
   POST /files/upload-url
   Body: { filename, contentType, purpose }
   
2. Server returns a presigned S3 PUT URL (valid 5 minutes)

3. Client uploads file directly to S3 using the presigned URL:
   PUT https://s3.example.com/bucket/uploads/{key}
   (no traffic through your application servers)

4. Client notifies server the upload is complete:
   POST /files/confirm
   Body: { key, metadata }

5. Server validates the file (size, type, virus scan) and moves
   from uploads/ prefix to final location
```

---

## Storage Structure

```
bucket: oms-files-{env}/
  products/images/{productId}/{filename}
  orders/{orderId}/invoice.pdf
  orders/{orderId}/return-label.pdf
  inventory/imports/{timestamp}-{filename}.csv
  orders/imports/{timestamp}-{filename}.csv
  support/{ticketId}/{filename}
  uploads/{uuid}/{filename}     ← temporary staging area
```

All paths are not guessable — use UUIDs. Presigned download URLs (time-limited) for private files.

---

## CSV Bulk Import Flow

### Bulk Order Import

```
Admin uploads CSV
  → POST /files/upload-url (purpose: ORDER_IMPORT)
  → PUT to S3 (direct)
  → POST /files/confirm → server validates CSV headers

Server publishes Kafka event: BulkOrderImportRequested { s3Key }

[Import Worker Service]
  - Downloads CSV from S3
  - Parses rows
  - Validates each row (customer exists, SKUs valid, quantities positive)
  - For valid rows: publishes OrderCreated events (normal saga flow)
  - For invalid rows: collects errors

  - Publishes ImportCompleted { s3Key, processedCount, errorCount, errorReportKey }

[Admin UI]
  - Receives WebSocket notification: import done
  - Shows summary + link to error report CSV
```

### Validation Rules (CSV)
- Required columns present (fail fast if schema mismatch)
- No more than 500 rows per file
- Customer email must match an existing user
- SKU must exist in inventory
- Quantity must be a positive integer
- Price must be a positive decimal

---

## File Validation (Server-Side)

After the client confirms upload:

1. **Size check**: verify S3 object size against declared content type limits
2. **MIME type check**: inspect magic bytes (not just extension or Content-Type header)
3. **Virus scan**: scan with ClamAV (async) — mark file as PENDING_SCAN, update to SAFE/INFECTED
4. **Image processing**: for product images, generate thumbnails (128px, 512px) via AWS Lambda or background job

Files marked INFECTED are deleted immediately and the upload is rejected.

---

## File Metadata (DB)

```
files (table in a shared files service or within relevant service):
  id              UUID     PRIMARY KEY
  owner_type      VARCHAR  -- 'PRODUCT', 'ORDER', 'SUPPORT_TICKET'
  owner_id        UUID
  purpose         VARCHAR  -- 'PRODUCT_IMAGE', 'INVOICE', 'RETURN_LABEL', etc.
  original_name   VARCHAR
  s3_key          VARCHAR  NOT NULL
  content_type    VARCHAR
  size_bytes      BIGINT
  scan_status     VARCHAR  -- PENDING, SAFE, INFECTED
  uploaded_by     UUID     -- user ID
  created_at      TIMESTAMPTZ
```

---

## Access Control

- Product images: **public** (served via CDN)
- Invoices / return labels: **private** — only accessible to order owner or admin
- CSV imports: **admin only**

For private files, never expose the S3 URL directly. Serve through:
```
GET /files/{fileId}/download
  → validates authorization
  → generates presigned GET URL (valid 15 minutes)
  → 302 redirect to presigned URL
```

---

## CDN for Product Images

Product images are served through CloudFront (or nginx in dev):

```
https://cdn.oms.example.com/products/images/{productId}/thumb-128.webp
https://cdn.oms.example.com/products/images/{productId}/thumb-512.webp
https://cdn.oms.example.com/products/images/{productId}/original.webp
```

Cache-Control: `max-age=31536000, immutable` (images are versioned by productId path, never mutated in place).

---

## Local Development

Use **MinIO** (S3-compatible, runs in Docker) instead of AWS S3:
- Same SDK, same API
- Data stored locally at `./minio-data/`
- Console at `localhost:9001`
