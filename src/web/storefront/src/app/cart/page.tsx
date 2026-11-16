"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Trash2, ArrowLeft, Tag, ShoppingBag, ShieldCheck } from "lucide-react";
import {
  fetchCart,
  updateCartItemQuantity,
  removeCartItem,
  applyCartCoupon,
  CartResponse,
  DEMO_USER_ID,
} from "@/lib/api-client";
import { formatVND } from "@/lib/utils";

const FIXED_SHIPPING_FEE = 30000; // 30,000 VND theo contracts/README.md

export default function CartPage() {
  const [cart, setCart] = useState<CartResponse | null>(null);
  const [couponCode, setCouponCode] = useState("");
  const [couponError, setCouponError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  const loadCart = async () => {
    try {
      const data = await fetchCart(DEMO_USER_ID);
      setCart(data);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCart();
  }, []);

  const handleUpdateQuantity = async (variantId: string, newQty: number) => {
    setActionLoading(true);
    try {
      const updated = await updateCartItemQuantity(DEMO_USER_ID, variantId, newQty);
      setCart(updated);
    } catch (err: any) {
      alert(err.message || "Lỗi khi cập nhật số lượng");
    } finally {
      setActionLoading(false);
    }
  };

  const handleRemove = async (variantId: string) => {
    setActionLoading(true);
    try {
      const updated = await removeCartItem(DEMO_USER_ID, variantId);
      setCart(updated);
    } catch (err: any) {
      alert(err.message || "Lỗi khi xóa sản phẩm");
    } finally {
      setActionLoading(false);
    }
  };

  const handleApplyCoupon = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!couponCode.trim()) return;

    setCouponError(null);
    setActionLoading(true);

    try {
      const updated = await applyCartCoupon(DEMO_USER_ID, couponCode.trim());
      setCart(updated);
      setCouponCode("");
    } catch (err: any) {
      setCouponError(err.message || "Mã coupon không hợp lệ");
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-7xl mx-auto px-4 py-20 text-center text-sm text-zinc-500">
        Đang tải thông tin giỏ hàng...
      </div>
    );
  }

  const items = cart?.items || [];
  const subtotal = parseFloat(cart?.subtotal?.amount || "0");
  const discount = parseFloat(cart?.discountAmount?.amount || "0");
  const finalTotal = Math.max(0, subtotal - discount + (items.length > 0 ? FIXED_SHIPPING_FEE : 0));

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
      <Link
        href="/"
        className="inline-flex items-center gap-2 text-xs font-semibold text-zinc-500 hover:text-zinc-900 transition-colors mb-6"
      >
        <ArrowLeft className="w-4 h-4" /> Tiếp tục mua sắm
      </Link>

      <h1 className="text-2xl sm:text-3xl font-extrabold text-zinc-950 mb-8">
        Giỏ Hàng Của Bạn ({items.reduce((acc, i) => acc + i.quantity, 0)})
      </h1>

      {items.length === 0 ? (
        <div className="bg-zinc-50 border border-zinc-200 rounded-lg p-12 text-center">
          <ShoppingBag className="w-12 h-12 text-zinc-400 mx-auto mb-4" />
          <h2 className="text-lg font-bold text-zinc-900 mb-2">Giỏ hàng của bạn đang trống</h2>
          <p className="text-sm text-zinc-500 mb-6">
            Hãy lựa chọn những bộ trang phục thời thượng nhất từ danh mục của chúng tôi.
          </p>
          <Link
            href="/#products"
            className="inline-block px-6 py-3 bg-zinc-900 text-white text-xs font-bold uppercase tracking-wider rounded hover:bg-zinc-800 transition-colors"
          >
            Khám phá sản phẩm
          </Link>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-10 items-start">
          {/* Cart Items Table */}
          <div className="lg:col-span-2 divide-y divide-zinc-100 border-t border-b border-zinc-100">
            {items.map((item) => (
              <div key={item.variantId} className="py-6 flex gap-4 sm:gap-6 items-center">
                <div className="w-20 h-24 bg-zinc-100 rounded overflow-hidden shrink-0">
                  {item.imageUrl ? (
                    <img
                      src={item.imageUrl}
                      alt={item.productName}
                      className="w-full h-full object-cover object-center"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-zinc-400 text-xs">
                      No img
                    </div>
                  )}
                </div>

                <div className="flex-1 min-w-0">
                  <h3 className="font-semibold text-sm text-zinc-950 truncate">
                    {item.productName}
                  </h3>
                  <p className="text-xs text-zinc-500 mt-0.5">
                    Size: <span className="font-medium text-zinc-800 uppercase">{item.size}</span> | Màu: <span className="font-medium text-zinc-800 uppercase">{item.color}</span>
                  </p>
                  <p className="text-xs font-semibold text-zinc-900 mt-2">
                    {formatVND(item.unitPrice.amount)}
                  </p>
                </div>

                {/* Quantity Controls */}
                <div className="flex items-center border border-zinc-300 rounded h-9">
                  <button
                    disabled={actionLoading}
                    onClick={() => handleUpdateQuantity(item.variantId, item.quantity - 1)}
                    className="w-8 h-full flex items-center justify-center text-zinc-600 hover:bg-zinc-100 text-xs font-bold"
                  >
                    -
                  </button>
                  <span className="w-8 text-center text-xs font-semibold text-zinc-900">
                    {item.quantity}
                  </span>
                  <button
                    disabled={actionLoading}
                    onClick={() => handleUpdateQuantity(item.variantId, item.quantity + 1)}
                    className="w-8 h-full flex items-center justify-center text-zinc-600 hover:bg-zinc-100 text-xs font-bold"
                  >
                    +
                  </button>
                </div>

                {/* Line Total */}
                <div className="text-right min-w-[90px]">
                  <p className="text-sm font-bold text-zinc-950">
                    {formatVND(item.lineTotal.amount)}
                  </p>
                </div>

                {/* Remove Button */}
                <button
                  disabled={actionLoading}
                  onClick={() => handleRemove(item.variantId)}
                  className="p-2 text-zinc-400 hover:text-red-600 transition-colors"
                  aria-label="Xóa sản phẩm"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>

          {/* Order Summary & Coupon */}
          <div className="bg-zinc-50 border border-zinc-200/80 rounded-lg p-6 flex flex-col gap-6">
            <h2 className="text-base font-bold text-zinc-950 uppercase tracking-wider pb-3 border-b border-zinc-200">
              Tóm Tắt Đơn Hàng
            </h2>

            {/* Pricing details */}
            <div className="space-y-3 text-sm">
              <div className="flex justify-between text-zinc-600">
                <span>Tạm tính hàng hóa</span>
                <span className="font-semibold text-zinc-900">{formatVND(subtotal)}</span>
              </div>

              {discount > 0 && (
                <div className="flex justify-between text-emerald-600 font-medium">
                  <span className="flex items-center gap-1">
                    <Tag className="w-3.5 h-3.5" /> Mã giảm giá ({cart?.coupon?.code})
                  </span>
                  <span>-{formatVND(discount)}</span>
                </div>
              )}

              <div className="flex justify-between text-zinc-600">
                <span>Phí vận chuyển tiêu chuẩn</span>
                <span className="font-semibold text-zinc-900">{formatVND(FIXED_SHIPPING_FEE)}</span>
              </div>

              <div className="pt-3 border-t border-zinc-200 flex justify-between items-baseline">
                <div>
                  <span className="font-bold text-zinc-950 block">Tổng thanh toán</span>
                  <span className="text-[11px] text-zinc-400">Đã bao gồm VAT và cước vận chuyển</span>
                </div>
                <span className="text-xl font-extrabold text-zinc-950">
                  {formatVND(finalTotal)}
                </span>
              </div>
            </div>

            {/* Coupon input */}
            <form onSubmit={handleApplyCoupon} className="pt-3 border-t border-zinc-200">
              <label className="block text-xs font-semibold text-zinc-800 uppercase tracking-wider mb-2">
                Áp dụng mã ưu đãi
              </label>
              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="Vd: SUMMER10, WELCOME20, FREESHIP"
                  value={couponCode}
                  onChange={(e) => setCouponCode(e.target.value.toUpperCase())}
                  className="flex-1 px-3 py-2 text-xs uppercase font-medium bg-white border border-zinc-300 rounded focus:outline-none focus:ring-1 focus:ring-zinc-900"
                />
                <button
                  type="submit"
                  disabled={actionLoading || !couponCode.trim()}
                  className="px-4 py-2 bg-zinc-900 text-white text-xs font-bold uppercase rounded hover:bg-zinc-800 transition-colors disabled:opacity-50"
                >
                  Áp dụng
                </button>
              </div>
              {couponError && (
                <p className="text-xs text-red-600 mt-2">{couponError}</p>
              )}
            </form>

            {/* Checkout CTA */}
            <button
              onClick={() => alert("Chức năng Checkout sẽ chuyển tiếp qua Saga Order-Service (Person A)!")}
              className="w-full py-3.5 bg-rose-600 text-white font-bold text-xs uppercase tracking-wider rounded hover:bg-rose-700 shadow-md transition-colors flex items-center justify-center gap-2"
            >
              <ShieldCheck className="w-4 h-4" /> Tiến hành đặt hàng
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
