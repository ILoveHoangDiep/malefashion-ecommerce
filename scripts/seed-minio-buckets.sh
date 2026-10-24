#!/bin/bash
set -e

echo "==> Dang khoi tao bucket 'malefashion-media' qua AWS S3 API..."

# 1. Tao bucket malefashion-media (ket noi truc tiep qua container namespace)
docker run --rm --network container:malefashion-minio \
  -e AWS_ACCESS_KEY_ID=minioadmin \
  -e AWS_SECRET_ACCESS_KEY=minioadminpassword \
  amazon/aws-cli \
  --endpoint-url http://localhost:9000 \
  s3 mb s3://malefashion-media || true

# 2. Thiet lap policy cho phep doc cong khai (public anonymous download)
docker run --rm --network container:malefashion-minio \
  -e AWS_ACCESS_KEY_ID=minioadmin \
  -e AWS_SECRET_ACCESS_KEY=minioadminpassword \
  amazon/aws-cli \
  --endpoint-url http://localhost:9000 \
  s3api put-bucket-policy \
  --bucket malefashion-media \
  --policy '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::malefashion-media/*"]}]}'

# 3. Kiem tra danh sach bucket
echo "==> Danh sach bucket hien co:"
docker run --rm --network container:malefashion-minio \
  -e AWS_ACCESS_KEY_ID=minioadmin \
  -e AWS_SECRET_ACCESS_KEY=minioadminpassword \
  amazon/aws-cli \
  --endpoint-url http://localhost:9000 \
  s3 ls

echo "==> Bucket 'malefashion-media' da duoc khoi tao va cap quyen public thanh cong!"