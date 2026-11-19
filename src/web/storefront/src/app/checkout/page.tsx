"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import {
  DEMO_USER_ID,
  fetchCart,
  validateCart,
  checkoutOrder,
  CartResponse,
  OrderResponse,
  PaymentMethod,
  MockPaymentScenario,
  ShippingAddress,
} from "@/lib/api-client";
import { formatVND } from "@/lib/utils";

const SHIPPING_FEE = 30000;

type FormErrors = Partial<Record<keyof ShippingAddress, string>>;

function validate(addr: ShippingAddress): FormErrors {
  const errs: FormErrors = {};
  if (!addr.recipientName.trim()) errs.recipientName = "Vui lòng nhập họ tên";
  if (!addr.phone.trim()) errs.phone = "Vui lòng nhập số điện thoại";
  else if (!/^(0|\+84)[0-9]{8,10}$/.test(addr.phone.trim()))
    errs.phone = "Số điện thoại không hợp lệ";
  if (!addr.addressLine.trim())
    errs.addressLine = "Vui lòng nhập địa chỉ cụ thể";
  if (!addr.ward.trim()) errs.ward = "Vui lòng nhập phường/xã";
  if (!addr.district.trim()) errs.district = "Vui lòng nhập quận/huyện";
  if (!addr.city.trim()) errs.city = "Vui lòng nhập tỉnh/thành phố";
  return errs;
}

export default function CheckoutPage() {
  const router = useRouter();

  // ── Cart state ─────────────────────────────────────────────────────────────
  const [cart, setCart] = useState<CartResponse | null>(null);
  const [cartLoading, setCartLoading] = useState(true);
  const [cartError, setCartError] = useState<string | null>(null);

  // ── Shipping form state ────────────────────────────────────────────────────
  const [address, setAddress] = useState<ShippingAddress>({
    recipientName: "",
    phone: "",
    addressLine: "",
    ward: "",
    district: "",
    city: "",
  });
  const [formErrors, setFormErrors] = useState<FormErrors>({});

  // ── Payment state ──────────────────────────────────────────────────────────
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("COD");
  const [mockScenario, setMockScenario] =
    useState<MockPaymentScenario>("APPROVED");

  // ── Submit state ───────────────────────────────────────────────────────────
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [orderResult, setOrderResult] = useState<OrderResponse | null>(null);

  // ── Load cart ──────────────────────────────────────────────────────────────
  useEffect(() => {
    setCartLoading(true);
    fetchCart(DEMO_USER_ID)
      .then((c) => {
        if (!c.items || c.items.length === 0) {
          setCartError("Giỏ hàng đang trống. Vui lòng thêm sản phẩm trước.");
        } else {
          setCart(c);
        }
      })
      .catch(() =>
        setCartError("Không tải được giỏ hàng. Vui lòng thử lại.")
      )
      .finally(() => setCartLoading(false));
  }, []);

  // ── Field helpers ──────────────────────────────────────────────────────────
  function handleField(field: keyof ShippingAddress, value: string) {
    setAddress((prev) => ({ ...prev, [field]: value }));
    if (formErrors[field]) {
      setFormErrors((prev) => ({ ...prev, [field]: undefined }));
    }
  }

  // ── Totals ─────────────────────────────────────────────────────────────────
  const subtotalNum = cart
    ? parseFloat(cart.subtotal.amount)
    : 0;
  const discountNum = cart
    ? parseFloat(cart.discountAmount.amount)
    : 0;
  const totalNum = subtotalNum - discountNum + SHIPPING_FEE;

  // ── Submit ─────────────────────────────────────────────────────────────────
  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitError(null);

    // Client-side validation
    const errs = validate(address);
    if (Object.keys(errs).length > 0) {
      setFormErrors(errs);
      return;
    }

    setSubmitting(true);
    try {
      // Validate cart trước khi checkout
      const validation = await validateCart(DEMO_USER_ID);
      if (!validation.valid) {
        setSubmitError(
          `Giỏ hàng không hợp lệ: ${validation.reason || "Vui lòng kiểm tra lại."}`
        );
        return;
      }

      const order = await checkoutOrder(DEMO_USER_ID, {
        cartId: cart!.cartId,
        couponCode: cart?.coupon?.code ?? null,
        shippingAddress: address,
        paymentMethod,
        mockPaymentScenario:
          paymentMethod === "MOCK_CARD" ? mockScenario : undefined,
      });

      setOrderResult(order);
    } catch (err: unknown) {
      setSubmitError(
        err instanceof Error ? err.message : "Đặt hàng thất bại. Thử lại sau."
      );
    } finally {
      setSubmitting(false);
    }
  }

  // ── Success screen ─────────────────────────────────────────────────────────
  if (orderResult) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center px-4">
        <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-8 text-center space-y-6">
          <div className="text-6xl">🎉</div>
          <h1 className="text-2xl font-bold text-gray-900">
            Đặt hàng thành công!
          </h1>
          <div className="bg-green-50 border border-green-200 rounded-xl p-4 space-y-1 text-left">
            <p className="text-sm text-gray-500">Mã đơn hàng</p>
            <p className="text-xl font-mono font-bold text-green-700">
              #{orderResult.orderNumber}
            </p>
            <p className="text-sm text-gray-500 mt-2">Trạng thái</p>
            <p className="text-sm font-semibold text-orange-600">
              {orderResult.status}
            </p>
            <p className="text-sm text-gray-500 mt-2">Tổng thanh toán</p>
            <p className="text-lg font-bold text-gray-900">
              {formatVND(parseFloat(orderResult.totalAmount.amount))}
            </p>
          </div>
          <p className="text-sm text-gray-500">
            Chúng tôi sẽ liên hệ xác nhận đơn hàng qua số điện thoại của bạn.
          </p>
          <div className="flex flex-col gap-3">
            <Link
              href="/"
              className="block bg-gray-900 text-white py-3 px-6 rounded-xl font-semibold hover:bg-gray-700 transition-colors"
            >
              Tiếp tục mua sắm
            </Link>
          </div>
        </div>
      </div>
    );
  }

  // ── Loading / Error ────────────────────────────────────────────────────────
  if (cartLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center space-y-3">
          <div className="animate-spin h-10 w-10 border-4 border-gray-900 border-t-transparent rounded-full mx-auto" />
          <p className="text-gray-500 text-sm">Đang tải giỏ hàng…</p>
        </div>
      </div>
    );
  }

  if (cartError) {
    return (
      <div className="min-h-screen flex items-center justify-center px-4">
        <div className="text-center space-y-4 max-w-sm">
          <p className="text-red-600">{cartError}</p>
          <Link
            href="/cart"
            className="inline-block bg-gray-900 text-white py-2 px-6 rounded-xl hover:bg-gray-700 transition-colors"
          >
            Quay lại giỏ hàng
          </Link>
        </div>
      </div>
    );
  }

  // ── Checkout form ──────────────────────────────────────────────────────────
  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-6xl mx-auto px-4 py-10">
        {/* Header */}
        <div className="mb-8">
          <Link
            href="/cart"
            className="text-sm text-gray-500 hover:text-gray-900 flex items-center gap-1"
          >
            ← Quay lại giỏ hàng
          </Link>
          <h1 className="text-3xl font-bold text-gray-900 mt-4">
            Thanh toán
          </h1>
        </div>

        <form onSubmit={handleSubmit} noValidate>
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
            {/* ── LEFT: Shipping + Payment ───────────────────────────────── */}
            <div className="lg:col-span-2 space-y-6">
              {/* Shipping Address */}
              <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
                <h2 className="text-lg font-semibold text-gray-900 mb-5">
                  Địa chỉ giao hàng
                </h2>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <Field
                    label="Họ và tên *"
                    value={address.recipientName}
                    onChange={(v) => handleField("recipientName", v)}
                    error={formErrors.recipientName}
                    placeholder="Nguyễn Văn A"
                    className="sm:col-span-2"
                  />
                  <Field
                    label="Số điện thoại *"
                    value={address.phone}
                    onChange={(v) => handleField("phone", v)}
                    error={formErrors.phone}
                    placeholder="0901234567"
                    type="tel"
                  />
                  <Field
                    label="Tỉnh / Thành phố *"
                    value={address.city}
                    onChange={(v) => handleField("city", v)}
                    error={formErrors.city}
                    placeholder="Hà Nội"
                  />
                  <Field
                    label="Quận / Huyện *"
                    value={address.district}
                    onChange={(v) => handleField("district", v)}
                    error={formErrors.district}
                    placeholder="Đống Đa"
                  />
                  <Field
                    label="Phường / Xã *"
                    value={address.ward}
                    onChange={(v) => handleField("ward", v)}
                    error={formErrors.ward}
                    placeholder="Khâm Thiên"
                  />
                  <Field
                    label="Địa chỉ cụ thể *"
                    value={address.addressLine}
                    onChange={(v) => handleField("addressLine", v)}
                    error={formErrors.addressLine}
                    placeholder="Số 1, Ngõ 100, Phố…"
                    className="sm:col-span-2"
                  />
                </div>
              </div>

              {/* Payment Method */}
              <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
                <h2 className="text-lg font-semibold text-gray-900 mb-5">
                  Phương thức thanh toán
                </h2>
                <div className="space-y-3">
                  <PaymentOption
                    id="cod"
                    label="Thanh toán khi nhận hàng (COD)"
                    icon="🏦"
                    checked={paymentMethod === "COD"}
                    onChange={() => setPaymentMethod("COD")}
                  />
                  <PaymentOption
                    id="mock-card"
                    label="Thẻ ngân hàng / Ví điện tử (Demo)"
                    icon="💳"
                    checked={paymentMethod === "MOCK_CARD"}
                    onChange={() => setPaymentMethod("MOCK_CARD")}
                  />
                </div>

                {paymentMethod === "MOCK_CARD" && (
                  <div className="mt-4 p-4 bg-blue-50 rounded-xl border border-blue-100 space-y-3">
                    <p className="text-xs font-medium text-blue-700 uppercase tracking-wide">
                      Kịch bản demo thanh toán
                    </p>
                    <div className="flex flex-wrap gap-2">
                      {(
                        [
                          ["APPROVED", "✅ Thành công"],
                          ["DECLINED", "❌ Từ chối"],
                          ["TIMEOUT", "⏱ Timeout"],
                        ] as [MockPaymentScenario, string][]
                      ).map(([val, lbl]) => (
                        <label
                          key={val}
                          className={`flex items-center gap-1.5 cursor-pointer px-3 py-1.5 rounded-lg text-sm border transition-colors ${
                            mockScenario === val
                              ? "bg-blue-600 text-white border-blue-600"
                              : "bg-white text-gray-700 border-gray-200 hover:border-blue-400"
                          }`}
                        >
                          <input
                            type="radio"
                            className="sr-only"
                            checked={mockScenario === val}
                            onChange={() => setMockScenario(val)}
                          />
                          {lbl}
                        </label>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* ── RIGHT: Order Summary ───────────────────────────────────── */}
            <div className="lg:col-span-1">
              <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 sticky top-6 space-y-5">
                <h2 className="text-lg font-semibold text-gray-900">
                  Đơn hàng
                </h2>

                {/* Items */}
                <ul className="divide-y divide-gray-100 max-h-72 overflow-y-auto">
                  {cart?.items.map((item) => (
                    <li key={item.variantId} className="flex gap-3 py-3">
                      {item.imageUrl ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img
                          src={item.imageUrl}
                          alt={item.productName}
                          className="w-14 h-14 rounded-lg object-cover flex-shrink-0 bg-gray-100"
                        />
                      ) : (
                        <div className="w-14 h-14 rounded-lg bg-gray-100 flex items-center justify-center text-xl flex-shrink-0">
                          👕
                        </div>
                      )}
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium text-gray-900 truncate">
                          {item.productName}
                        </p>
                        <p className="text-xs text-gray-400">
                          {item.size} · {item.color} · x{item.quantity}
                        </p>
                        <p className="text-sm font-semibold text-gray-900 mt-0.5">
                          {formatVND(parseFloat(item.lineTotal.amount))}
                        </p>
                      </div>
                    </li>
                  ))}
                </ul>

                {/* Totals */}
                <div className="space-y-2 border-t border-gray-100 pt-4">
                  <SummaryRow
                    label="Tạm tính"
                    value={formatVND(subtotalNum)}
                  />
                  {discountNum > 0 && (
                    <SummaryRow
                      label={`Giảm giá (${cart?.coupon?.code})`}
                      value={`− ${formatVND(discountNum)}`}
                      valueClass="text-green-600"
                    />
                  )}
                  <SummaryRow
                    label="Phí vận chuyển"
                    value={formatVND(SHIPPING_FEE)}
                  />
                  <div className="border-t border-gray-200 pt-2 flex justify-between items-center">
                    <span className="font-bold text-gray-900">Tổng cộng</span>
                    <span className="font-bold text-xl text-gray-900">
                      {formatVND(totalNum)}
                    </span>
                  </div>
                </div>

                {/* Error */}
                {submitError && (
                  <div className="bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
                    {submitError}
                  </div>
                )}

                {/* CTA */}
                <button
                  type="submit"
                  disabled={submitting}
                  className="w-full bg-gray-900 text-white py-3.5 rounded-xl font-semibold text-sm hover:bg-gray-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2"
                >
                  {submitting ? (
                    <>
                      <span className="animate-spin h-4 w-4 border-2 border-white border-t-transparent rounded-full" />
                      Đang xử lý…
                    </>
                  ) : (
                    "Đặt hàng"
                  )}
                </button>

                <p className="text-xs text-gray-400 text-center">
                  Bằng cách đặt hàng, bạn đồng ý với{" "}
                  <span className="underline cursor-pointer">
                    điều khoản dịch vụ
                  </span>{" "}
                  của MaleFashion.
                </p>
              </div>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Helper components ─────────────────────────────────────────────────────────

function Field({
  label,
  value,
  onChange,
  error,
  placeholder,
  type = "text",
  className = "",
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  error?: string;
  placeholder?: string;
  type?: string;
  className?: string;
}) {
  return (
    <div className={className}>
      <label className="block text-sm font-medium text-gray-700 mb-1">
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className={`w-full px-3 py-2.5 border rounded-xl text-sm outline-none focus:ring-2 transition-shadow ${
          error
            ? "border-red-400 focus:ring-red-200"
            : "border-gray-200 focus:ring-gray-900/20 focus:border-gray-400"
        }`}
      />
      {error && <p className="text-xs text-red-600 mt-1">{error}</p>}
    </div>
  );
}

function PaymentOption({
  id,
  label,
  icon,
  checked,
  onChange,
}: {
  id: string;
  label: string;
  icon: string;
  checked: boolean;
  onChange: () => void;
}) {
  return (
    <label
      htmlFor={id}
      className={`flex items-center gap-3 p-4 border rounded-xl cursor-pointer transition-colors ${
        checked
          ? "border-gray-900 bg-gray-900/5"
          : "border-gray-200 hover:border-gray-400"
      }`}
    >
      <input
        id={id}
        type="radio"
        className="accent-gray-900 w-4 h-4"
        checked={checked}
        onChange={onChange}
      />
      <span className="text-xl">{icon}</span>
      <span className="text-sm font-medium text-gray-900">{label}</span>
    </label>
  );
}

function SummaryRow({
  label,
  value,
  valueClass = "text-gray-900",
}: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <div className="flex justify-between text-sm text-gray-600">
      <span>{label}</span>
      <span className={`font-medium ${valueClass}`}>{value}</span>
    </div>
  );
}
