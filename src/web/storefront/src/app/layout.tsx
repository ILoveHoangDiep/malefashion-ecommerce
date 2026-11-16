import type { Metadata } from "next";
import Link from "next/link";
import { ShoppingBag, Search, User } from "lucide-react";
import "./globals.css";

export const metadata: Metadata = {
  title: "MaleFashion | Thời Trang Nam Hiện Đại & Tinh Tế",
  description: "Bộ sưu tập trang phục và phụ kiện thời trang nam cao cấp, chuẩn form dáng, chất liệu vượt trội.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="vi">
      <body className="min-h-screen flex flex-col bg-white text-zinc-900 antialiased">
        {/* Top announcement */}
        <div className="bg-zinc-900 text-zinc-300 text-xs py-2 px-4 text-center font-medium">
          Ưu đãi mùa lễ hội: Nhập mã <span className="text-white font-bold tracking-wider">FREESHIP</span> miễn phí vận chuyển cho đơn từ 150.000đ | Giảm 20% với <span className="text-white font-bold tracking-wider">WELCOME20</span>
        </div>

        {/* Main Header */}
        <header className="sticky top-0 z-50 bg-white/95 backdrop-blur border-b border-zinc-100">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
            {/* Logo */}
            <div className="flex items-center gap-8">
              <Link href="/" className="text-2xl font-black tracking-tight text-zinc-950 uppercase">
                MALE<span className="text-rose-600">.</span>FASHION
              </Link>
              <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-zinc-600">
                <Link href="/" className="hover:text-zinc-950 transition-colors">
                  Trang chủ
                </Link>
                <Link href="/#products" className="hover:text-zinc-950 transition-colors">
                  Sản phẩm
                </Link>
                <Link href="/#lookbook" className="hover:text-zinc-950 transition-colors">
                  Bộ sưu tập
                </Link>
                <Link href="/cart" className="hover:text-zinc-950 transition-colors">
                  Giỏ hàng
                </Link>
              </nav>
            </div>

            {/* Actions */}
            <div className="flex items-center gap-4">
              <button aria-label="Tìm kiếm" className="p-2 text-zinc-700 hover:text-zinc-950 transition-colors">
                <Search className="w-5 h-5" />
              </button>
              <button aria-label="Tài khoản" className="p-2 text-zinc-700 hover:text-zinc-950 transition-colors">
                <User className="w-5 h-5" />
              </button>
              <Link
                href="/cart"
                className="relative p-2 text-zinc-950 hover:opacity-80 transition-opacity"
                aria-label="Xem giỏ hàng"
              >
                <ShoppingBag className="w-6 h-6" />
                <span className="absolute top-1 right-1 w-2.5 h-2.5 bg-rose-600 rounded-full ring-2 ring-white" />
              </Link>
            </div>
          </div>
        </header>

        {/* Content */}
        <main className="flex-1">{children}</main>

        {/* Footer */}
        <footer className="bg-zinc-950 text-zinc-400 py-12 border-t border-zinc-900 mt-16">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 grid grid-cols-1 md:grid-cols-4 gap-8">
            <div>
              <h3 className="text-white text-lg font-bold tracking-tight mb-4 uppercase">
                MALE<span className="text-rose-600">.</span>FASHION
              </h3>
              <p className="text-sm leading-relaxed text-zinc-400">
                Thương hiệu thời trang nam cao cấp, định hình phong cách hiện đại và lịch lãm cho phái mạnh Việt Nam.
              </p>
            </div>
            <div>
              <h4 className="text-white text-sm font-semibold uppercase tracking-wider mb-4">Danh mục</h4>
              <ul className="space-y-2 text-sm">
                <li><Link href="/#products" className="hover:text-white transition-colors">Áo sơ mi công sở</Link></li>
                <li><Link href="/#products" className="hover:text-white transition-colors">Áo thun cotton</Link></li>
                <li><Link href="/#products" className="hover:text-white transition-colors">Quần âu & Chino</Link></li>
                <li><Link href="/#products" className="hover:text-white transition-colors">Áo khoác nam</Link></li>
              </ul>
            </div>
            <div>
              <h4 className="text-white text-sm font-semibold uppercase tracking-wider mb-4">Hỗ trợ khách hàng</h4>
              <ul className="space-y-2 text-sm">
                <li><span className="hover:text-white cursor-pointer transition-colors">Chính sách đổi trả 30 ngày</span></li>
                <li><span className="hover:text-white cursor-pointer transition-colors">Hướng dẫn chọn size chuẩn</span></li>
                <li><span className="hover:text-white cursor-pointer transition-colors">Kiểm tra trạng thái đơn hàng</span></li>
                <li><span className="hover:text-white cursor-pointer transition-colors">Chính sách bảo mật</span></li>
              </ul>
            </div>
            <div>
              <h4 className="text-white text-sm font-semibold uppercase tracking-wider mb-4">Hệ thống & Kết nối</h4>
              <p className="text-sm text-zinc-400 mb-2">Hà Nội, Việt Nam</p>
              <p className="text-sm text-zinc-400 mb-4">Hotline: 1900 6868</p>
              <div className="text-xs text-zinc-500">
                Microservices Architecture • Spring Boot 3 & Next.js 14
              </div>
            </div>
          </div>
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 pt-8 mt-8 border-t border-zinc-900 text-xs text-center text-zinc-500">
            © 2026 MaleFashion Inc. IT4409 Monorepo Architecture. All rights reserved.
          </div>
        </footer>
      </body>
    </html>
  );
}
