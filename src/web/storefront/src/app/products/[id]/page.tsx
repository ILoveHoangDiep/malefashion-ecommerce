"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Check, ShoppingBag, ShieldAlert } from "lucide-react";
import { fetchProductById, addItemToCart, ProductDetail, Variant } from "@/lib/api-client";
import { formatVND } from "@/lib/utils";

export default function ProductDetailPage({ params }: { params: { id: string } }) {
  const router = useRouter();
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [selectedVariant, setSelectedVariant] = useState<Variant | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState(false);

  useEffect(() => {
    fetchProductById(params.id)
      .then((data) => {
        setProduct(data);
        if (data && data.variants && data.variants.length > 0) {
          setSelectedVariant(data.variants[0]);
        }
      })
      .catch((err) => setErrorMsg(err.message))
      .finally(() => setLoading(false));
  }, [params.id]);

  const handleAddToCart = async () => {
    if (!product || !selectedVariant) return;

    setAdding(true);
    setErrorMsg(null);
    setSuccessMsg(false);

    try {
      await addItemToCart("9a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", {
        variantId: selectedVariant.variantId,
        sku: selectedVariant.sku,
        productName: product.name,
        size: selectedVariant.attributes.size,
        color: selectedVariant.attributes.color,
        imageUrl: selectedVariant.imageUrl || undefined,
        unitPrice: parseFloat(selectedVariant.price.amount),
        quantity,
      });

      setSuccessMsg(true);
      setTimeout(() => {
        router.push("/cart");
      }, 800);
    } catch (err: any) {
      setErrorMsg(err.message || "Không thể thêm vào giỏ hàng");
    } finally {
      setAdding(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-7xl mx-auto px-4 py-20 text-center text-sm text-zinc-500">
        Đang tải thông tin sản phẩm...
      </div>
    );
  }

  if (!product) {
    return (
      <div className="max-w-7xl mx-auto px-4 py-20 text-center">
        <h2 className="text-xl font-bold text-zinc-900 mb-2">Không tìm thấy sản phẩm</h2>
        <Link href="/" className="text-sm text-rose-600 hover:underline">
          Quay lại trang chủ
        </Link>
      </div>
    );
  }

  const sizes = Array.from(new Set(product.variants.map((v) => v.attributes.size)));
  const colors = Array.from(new Set(product.variants.map((v) => v.attributes.color)));

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
      <Link
        href="/"
        className="inline-flex items-center gap-2 text-xs font-semibold text-zinc-500 hover:text-zinc-900 transition-colors mb-8"
      >
        <ArrowLeft className="w-4 h-4" /> Quay lại danh sách sản phẩm
      </Link>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-12 items-start">
        {/* Product Image */}
        <div className="aspect-[3/4] bg-zinc-100 rounded-lg overflow-hidden border border-zinc-100">
          {selectedVariant?.imageUrl ? (
            <img
              src={selectedVariant.imageUrl}
              alt={product.name}
              className="w-full h-full object-cover object-center"
            />
          ) : (
            <div className="w-full h-full flex items-center justify-center text-zinc-400 text-sm">
              Không có ảnh sản phẩm
            </div>
          )}
        </div>

        {/* Product Meta & Actions */}
        <div className="flex flex-col">
          <span className="text-xs uppercase font-bold tracking-wider text-rose-600 mb-2">
            {product.categoryName}
          </span>
          <h1 className="text-2xl sm:text-3xl font-extrabold text-zinc-950 mb-3">
            {product.name}
          </h1>

          {/* Pricing */}
          <div className="text-2xl font-bold text-zinc-900 mb-6">
            {selectedVariant ? formatVND(selectedVariant.price.amount) : "Liên hệ"}
          </div>

          {/* Description */}
          <p className="text-sm text-zinc-600 leading-relaxed mb-8 border-y border-zinc-100 py-4">
            {product.description || "Chất liệu cao cấp, độ hoàn thiện tinh xảo từng đường may."}
          </p>

          {/* Size Selector */}
          <div className="mb-6">
            <label className="block text-xs font-bold uppercase tracking-wider text-zinc-800 mb-3">
              Kích thước: {selectedVariant?.attributes.size}
            </label>
            <div className="flex flex-wrap gap-2">
              {sizes.map((s) => {
                const isSelected = selectedVariant?.attributes.size === s;
                return (
                  <button
                    key={s}
                    onClick={() => {
                      const match = product.variants.find((v) => v.attributes.size === s);
                      if (match) setSelectedVariant(match);
                    }}
                    className={`min-w-[44px] h-11 px-3 text-xs font-semibold rounded border transition-colors ${
                      isSelected
                        ? "bg-zinc-900 text-white border-zinc-900"
                        : "bg-white text-zinc-800 border-zinc-300 hover:border-zinc-400"
                    }`}
                  >
                    {s}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Color Selector */}
          <div className="mb-6">
            <label className="block text-xs font-bold uppercase tracking-wider text-zinc-800 mb-3">
              Màu sắc: {selectedVariant?.attributes.color}
            </label>
            <div className="flex flex-wrap gap-2">
              {colors.map((c) => {
                const isSelected = selectedVariant?.attributes.color === c;
                return (
                  <button
                    key={c}
                    onClick={() => {
                      const match = product.variants.find((v) => v.attributes.color === c);
                      if (match) setSelectedVariant(match);
                    }}
                    className={`px-4 h-11 text-xs font-semibold rounded border uppercase tracking-wider transition-colors ${
                      isSelected
                        ? "bg-zinc-900 text-white border-zinc-900"
                        : "bg-white text-zinc-800 border-zinc-300 hover:border-zinc-400"
                    }`}
                  >
                    {c}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Quantity Selector */}
          <div className="mb-8">
            <label className="block text-xs font-bold uppercase tracking-wider text-zinc-800 mb-3">
              Số lượng
            </label>
            <div className="flex items-center border border-zinc-300 rounded w-32 h-11">
              <button
                type="button"
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                className="w-10 h-full flex items-center justify-center text-zinc-600 hover:bg-zinc-100 font-bold"
              >
                -
              </button>
              <span className="flex-1 text-center text-sm font-semibold text-zinc-900">
                {quantity}
              </span>
              <button
                type="button"
                onClick={() => setQuantity((q) => q + 1)}
                className="w-10 h-full flex items-center justify-center text-zinc-600 hover:bg-zinc-100 font-bold"
              >
                +
              </button>
            </div>
          </div>

          {/* Error Message */}
          {errorMsg && (
            <div className="flex items-center gap-2 p-3 bg-red-50 text-red-700 text-xs rounded mb-4 border border-red-100">
              <ShieldAlert className="w-4 h-4 shrink-0" />
              <span>{errorMsg}</span>
            </div>
          )}

          {/* Success Message */}
          {successMsg && (
            <div className="flex items-center gap-2 p-3 bg-emerald-50 text-emerald-700 text-xs rounded mb-4 border border-emerald-100">
              <Check className="w-4 h-4 shrink-0" />
              <span>Đã thêm vào giỏ hàng! Đang chuyển hướng...</span>
            </div>
          )}

          {/* Add To Cart Button */}
          <button
            onClick={handleAddToCart}
            disabled={adding || !selectedVariant}
            className="w-full h-12 bg-zinc-950 text-white text-sm font-bold uppercase tracking-wider rounded hover:bg-zinc-800 transition-colors flex items-center justify-center gap-2 disabled:opacity-50"
          >
            <ShoppingBag className="w-4 h-4" />
            {adding ? "Đang xử lý..." : "Thêm vào giỏ hàng"}
          </button>
        </div>
      </div>
    </div>
  );
}
