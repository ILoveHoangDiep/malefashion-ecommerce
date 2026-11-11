# MaleFashion Storefront

Giao diện khách hàng thời trang nam hiện đại, xây dựng bằng **Next.js 14 (App Router)**, **TypeScript**, **Tailwind CSS**, và **Lucide Icons**.

## 1. Tính năng chính
- **Trang chủ & Lookbook**: Hero banner ấn tượng, bộ sưu tập theo mùa, giới thiệu giá trị thương hiệu.
- **Duyệt & Lọc sản phẩm**: Tích hợp danh mục từ `catalog-service`, lọc theo danh mục, hiển thị khoảng giá và biến thể.
- **Trang chi tiết sản phẩm**: Chọn biến thể (kích thước S/M/L/XL, màu sắc), thay đổi giá tương ứng theo SKU, bộ chọn số lượng, thêm vào giỏ hàng.
- **Quản lý giỏ hàng**: Đọc và cập nhật trực tiếp với `cart-coupon-service` trên Redis: tăng/giảm số lượng, xóa item.
- **Áp dụng mã giảm giá (Voucher engine)**: Kiểm tra và áp dụng mã (`SUMMER10`, `WELCOME20`, `FREESHIP`), tự động tính toán mức giảm và hiển thị tổng thanh toán (bao gồm phí ship 30.000 VND).

## 2. Hướng dẫn chạy cục bộ
```bash
cd src/web/storefront

# Cài đặt dependencies:
npm install

# Chạy server phát triển:
npm run dev
```
Mở trình duyệt tại: [http://localhost:3000](http://localhost:3000)

## 3. Chạy với Docker
```bash
docker build -t malefashion-storefront .
docker run -p 3000:3000 malefashion-storefront
```
