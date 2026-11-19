#!/usr/bin/env bash
# =============================================================================
# seed-minio-buckets.sh – Tạo MinIO buckets cho dự án malefashion-ecommerce
# Chạy sau khi MinIO container đã up (docker compose up -d minio)
# =============================================================================
set -euo pipefail

MINIO_CONTAINER="${MINIO_CONTAINER:-minio}"
MINIO_ALIAS="local"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"
MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://minio:9000}"

BUCKETS=(
  "catalog-images"
  "malefashion-media"
)

echo "============================================================"
echo " MinIO Bucket Seeder – malefashion-ecommerce"
echo "============================================================"
echo " Endpoint : $MINIO_ENDPOINT"
echo " Container: $MINIO_CONTAINER"
echo ""

# ── Cấu hình alias mc ────────────────────────────────────────────────────────
docker exec "$MINIO_CONTAINER" mc alias set "$MINIO_ALIAS" \
  "$MINIO_ENDPOINT" \
  "$MINIO_ROOT_USER" \
  "$MINIO_ROOT_PASSWORD" --quiet

# ── Tạo buckets & đặt policy public-read ─────────────────────────────────────
for BUCKET in "${BUCKETS[@]}"; do
  if docker exec "$MINIO_CONTAINER" mc ls "${MINIO_ALIAS}/${BUCKET}" > /dev/null 2>&1; then
    echo "[SKIP] Bucket '${BUCKET}' đã tồn tại."
  else
    docker exec "$MINIO_CONTAINER" mc mb "${MINIO_ALIAS}/${BUCKET}"
    echo "[OK]   Bucket '${BUCKET}' đã được tạo."
  fi

  # Đặt anonymous download (public-read) để catalog-service trả URL ảnh trực tiếp
  docker exec "$MINIO_CONTAINER" mc anonymous set download "${MINIO_ALIAS}/${BUCKET}"
  echo "[OK]   Policy 'download' đã áp dụng cho '${BUCKET}'."
done

echo ""
echo "✅ Seed MinIO buckets hoàn tất."
