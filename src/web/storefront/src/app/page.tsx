import Link from "next/link";
import { ArrowRight, ShieldCheck, Truck, RefreshCw, Award } from "lucide-react";
import { fetchCategories, fetchProducts } from "@/lib/api-client";
import { formatVND } from "@/lib/utils";

export default async function HomePage({
  searchParams,
}: {
  searchParams: { category?: string };
}) {
  const selectedCategory = searchParams.category || "all";
  const [categories, products] = await Promise.all([
    fetchCategories(),
    fetchProducts(selectedCategory),
  ]);

  return (
    <div>
      {/* Hero Section */}
      <section className="relative bg-zinc-900 text-white overflow-hidden">
        <div className="absolute inset-0 z-0 opacity-40 bg-[radial-gradient(#4b5563_1px,transparent_1px)] [background-size:16px_16px]" />
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-24 md:py-32 relative z-10 flex flex-col items-start justify-center">
          <span className="inline-block px-3 py-1 bg-rose-600/20 text-rose-400 border border-rose-500/30 rounded-full text-xs font-semibold tracking-wider uppercase mb-6">
            Autumn / Winter Collection 2026
          </span>
          <h1 className="text-4xl sm:text-6xl font-extrabold tracking-tight max-w-3xl leading-[1.1] mb-6">
            Định Hình Phong Cách. <br />
            <span className="text-zinc-400">Tối Giản & Chuẩn Mực.</span>
          </h1>
          <p className="text-zinc-300 text-base sm:text-lg max-w-xl mb-8 leading-relaxed">
            Khám phá các thiết kế thời trang nam hiện đại, form dáng tinh tế với chất liệu cao cấp cho từng cử động tự tin nhất.
          </p>
          <div className="flex flex-wrap gap-4">
            <Link
              href="#products"
              className="px-7 py-3.5 bg-white text-zinc-950 font-semibold rounded-md shadow-lg hover:bg-zinc-100 transition-colors flex items-center gap-2"
            >
              Xem bộ sưu tập <ArrowRight className="w-4 h-4" />
            </Link>
            <Link
              href="/cart"
              className="px-7 py-3.5 bg-zinc-800 text-white font-semibold rounded-md border border-zinc-700 hover:bg-zinc-700 transition-colors"
            >
              Giỏ hàng của bạn
            </Link>
          </div>
        </div>
      </section>

      {/* Feature Badges */}
      <section className="border-b border-zinc-100 bg-zinc-50/60 py-6">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 grid grid-cols-2 md:grid-cols-4 gap-6">
          <div className="flex items-center gap-3">
            <Truck className="w-6 h-6 text-zinc-800 shrink-0" />
            <div>
              <p className="text-xs font-bold uppercase text-zinc-900">Giao hàng toàn quốc</p>
              <p className="text-xs text-zinc-500">Mã FREESHIP đơn từ 150K</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <RefreshCw className="w-6 h-6 text-zinc-800 shrink-0" />
            <div>
              <p className="text-xs font-bold uppercase text-zinc-900">Đổi trả 30 ngày</p>
              <p className="text-xs text-zinc-500">Thủ tục nhanh chóng, tiện lợi</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <ShieldCheck className="w-6 h-6 text-zinc-800 shrink-0" />
            <div>
              <p className="text-xs font-bold uppercase text-zinc-900">Chất lượng tuyển chọn</p>
              <p className="text-xs text-zinc-500">100% sợi tự nhiên cao cấp</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <Award className="w-6 h-6 text-zinc-800 shrink-0" />
            <div>
              <p className="text-xs font-bold uppercase text-zinc-900">Hỗ trợ 24/7</p>
              <p className="text-xs text-zinc-500">Tư vấn chọn size chuẩn xác</p>
            </div>
          </div>
        </div>
      </section>

      {/* Catalog & Products Section */}
      <section id="products" className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <div className="flex flex-col md:flex-row md:items-end justify-between mb-8 gap-4">
          <div>
            <h2 className="text-2xl sm:text-3xl font-bold tracking-tight text-zinc-900">
              Sản Phẩm Nam Nổi Bật
            </h2>
            <p className="text-sm text-zinc-500 mt-1">
              Tuyển chọn các thiết kế bán chạy nhất trong tuần
            </p>
          </div>

          {/* Category Filter Pills */}
          <div className="flex flex-wrap gap-2">
            {categories.map((cat) => {
              const isActive = selectedCategory === cat.slug || (selectedCategory === "all" && cat.slug === "all");
              return (
                <Link
                  key={cat.id}
                  href={`/?category=${cat.slug}#products`}
                  className={`px-4 py-1.5 text-xs font-medium rounded-full transition-colors ${
                    isActive
                      ? "bg-zinc-900 text-white"
                      : "bg-zinc-100 text-zinc-700 hover:bg-zinc-200"
                  }`}
                >
                  {cat.name}
                </Link>
              );
            })}
          </div>
        </div>

        {/* Product Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-6">
          {products.map((prod) => (
            <div
              key={prod.productId}
              className="group flex flex-col bg-white border border-zinc-100 rounded-lg overflow-hidden hover:shadow-md transition-shadow"
            >
              <div className="relative aspect-[3/4] bg-zinc-100 overflow-hidden">
                {prod.primaryImageUrl ? (
                  <img
                    src={prod.primaryImageUrl}
                    alt={prod.name}
                    className="w-full h-full object-cover object-center group-hover:scale-105 transition-transform duration-300"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-zinc-400 text-xs">
                    No image
                  </div>
                )}
                <span className="absolute top-3 left-3 bg-zinc-900/80 text-white text-[10px] uppercase font-bold tracking-wider px-2 py-0.5 rounded">
                  {prod.categoryName}
                </span>
              </div>

              <div className="p-4 flex flex-col flex-1 justify-between">
                <div>
                  <h3 className="font-semibold text-sm text-zinc-900 group-hover:text-rose-600 transition-colors line-clamp-1">
                    {prod.name}
                  </h3>
                  <p className="text-xs text-zinc-500 mt-1">
                    {prod.variantCount} biến thể kích thước / màu
                  </p>
                </div>

                <div className="mt-4 pt-3 border-t border-zinc-50 flex items-center justify-between">
                  <div>
                    <span className="text-xs text-zinc-400 block">Giá từ</span>
                    <span className="text-sm font-bold text-zinc-950">
                      {formatVND(prod.minPrice.amount)}
                    </span>
                  </div>
                  <Link
                    href={`/products/${prod.productId}`}
                    className="text-xs font-semibold px-3 py-1.5 bg-zinc-900 text-white rounded hover:bg-zinc-800 transition-colors"
                  >
                    Xem chi tiết
                  </Link>
                </div>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Lookbook Banner */}
      <section id="lookbook" className="bg-zinc-950 text-white py-20 my-12">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 grid grid-cols-1 md:grid-cols-2 gap-12 items-center">
          <div>
            <span className="text-rose-500 text-xs font-bold tracking-widest uppercase">
              EDITORIAL LOOKBOOK
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold tracking-tight mt-2 mb-6">
              MaleFashion Monorepo Edition
            </h2>
            <p className="text-zinc-400 text-sm leading-relaxed mb-6">
              Mỗi sản phẩm đều được kiểm định nghiêm ngặt từ sợi vải, đường may tới độ bền màu. Thiết kế tối giản tôn lên khí chất đĩnh đạc và phong thái tự tin trong mọi hoàn cảnh.
            </p>
            <div className="p-4 rounded-lg bg-zinc-900 border border-zinc-800 text-xs text-zinc-300 space-y-1">
              <p><strong className="text-white">Kiến trúc:</strong> Spring Boot 3 microservices & Next.js 14</p>
              <p><strong className="text-white">Catalog Engine:</strong> MinIO S3 media + Redis Cache-aside</p>
              <p><strong className="text-white">Cart & Voucher:</strong> Redis POJO persistence & Flyway PostgreSQL rules</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <img
              src="https://images.unsplash.com/photo-1516257984-b1b4d707412e?w=600&auto=format&fit=crop&q=80"
              alt="Lookbook 1"
              className="rounded-lg object-cover w-full h-64 shadow-lg"
            />
            <img
              src="https://images.unsplash.com/photo-1507679799987-c73779587ccf?w=600&auto=format&fit=crop&q=80"
              alt="Lookbook 2"
              className="rounded-lg object-cover w-full h-64 shadow-lg mt-8"
            />
          </div>
        </div>
      </section>
    </div>
  );
}
