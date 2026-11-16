export interface Money {
  amount: string;
  currency: string;
}

export interface ProductSummary {
  productId: string;
  name: string;
  slug: string;
  categoryName: string;
  minPrice: Money;
  maxPrice: Money;
  primaryImageUrl: string | null;
  variantCount: number;
}

export interface VariantAttributes {
  size: string;
  color: string;
}

export interface Variant {
  variantId: string;
  sku: string;
  productId: string;
  productName: string;
  price: Money;
  attributes: VariantAttributes;
  imageUrl: string | null;
  active: boolean;
}

export interface ProductDetail {
  productId: string;
  name: string;
  slug: string;
  description: string;
  categoryName: string;
  variants: Variant[];
}

export interface Category {
  id: string;
  name: string;
  slug: string;
  description?: string;
  parentId?: string | null;
}

export interface CartItem {
  variantId: string;
  sku: string;
  productName: string;
  size: string;
  color: string;
  imageUrl?: string;
  quantity: number;
  unitPrice: Money;
  lineTotal: Money;
}

export interface AppliedCoupon {
  code: string;
  discountAmount: Money;
}

export interface CartResponse {
  cartId: string;
  userId: string;
  currency: string;
  items: CartItem[];
  coupon?: AppliedCoupon | null;
  subtotal: Money;
  discountAmount: Money;
  totalAmount: Money;
  updatedAt: string;
}

export interface CouponItem {
  id: string;
  code: string;
  description: string;
  discountType: string;
  discountValue: number;
  minOrderAmount?: number;
  maxDiscountAmount?: number;
}

const GATEWAY_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const CATALOG_DIRECT = "http://localhost:8082";
const CART_DIRECT = "http://localhost:8083";

// Fallback demo user ID
export const DEMO_USER_ID = "9a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

export async function fetchCategories(): Promise<Category[]> {
  try {
    const res = await fetch(`${CATALOG_DIRECT}/api/v1/categories`, { cache: "no-store" });
    if (res.ok) return await res.json();
  } catch (ignored) {}

  return [
    { id: "1", name: "Tất cả", slug: "all" },
    { id: "2", name: "Áo sơ mi", slug: "ao-so-mi" },
    { id: "3", name: "Áo thun nam", slug: "ao-thun" },
    { id: "4", name: "Quần âu & Chino", slug: "quan" },
    { id: "5", name: "Áo khoác", slug: "ao-khoac" },
  ];
}

export async function fetchProducts(categoryId?: string): Promise<ProductSummary[]> {
  try {
    const url = new URL(`${CATALOG_DIRECT}/api/v1/products`);
    if (categoryId && categoryId !== "all" && categoryId !== "1") {
      url.searchParams.append("categoryId", categoryId);
    }
    const res = await fetch(url.toString(), { cache: "no-store" });
    if (res.ok) {
      const data = await res.json();
      if (data.content && Array.isArray(data.content)) {
        return data.content;
      }
    }
  } catch (ignored) {}

  // Fallback sample catalog for instant UI preview
  return [
    {
      productId: "a9a46d2d-3802-5696-a45b-640da2d18386",
      name: "Basic Premium Cotton Tee",
      slug: "basic-tee",
      categoryName: "Áo thun nam",
      minPrice: { amount: "299000.00", currency: "VND" },
      maxPrice: { amount: "329000.00", currency: "VND" },
      primaryImageUrl: "https://images.unsplash.com/photo-1521572267360-ee0c2909d518?w=800&auto=format&fit=crop&q=80",
      variantCount: 4,
    },
    {
      productId: "b8b37c1c-2901-4585-b34a-530ca1c07275",
      name: "Oxford Long Sleeve Shirt",
      slug: "oxford-shirt",
      categoryName: "Áo sơ mi",
      minPrice: { amount: "489000.00", currency: "VND" },
      maxPrice: { amount: "520000.00", currency: "VND" },
      primaryImageUrl: "https://images.unsplash.com/photo-1602810318383-e386cc2a3ccf?w=800&auto=format&fit=crop&q=80",
      variantCount: 6,
    },
    {
      productId: "c7c26b0b-1890-3474-a239-429b90b96164",
      name: "Slim Fit Stretch Chino",
      slug: "slim-fit-chino",
      categoryName: "Quần âu & Chino",
      minPrice: { amount: "550000.00", currency: "VND" },
      maxPrice: { amount: "590000.00", currency: "VND" },
      primaryImageUrl: "https://images.unsplash.com/photo-1473966968600-fa801b869a1a?w=800&auto=format&fit=crop&q=80",
      variantCount: 4,
    },
    {
      productId: "d6d15a9a-0789-2363-9128-318a8f085053",
      name: "Classic Denim Trucker Jacket",
      slug: "denim-jacket",
      categoryName: "Áo khoác",
      minPrice: { amount: "899000.00", currency: "VND" },
      maxPrice: { amount: "950000.00", currency: "VND" },
      primaryImageUrl: "https://images.unsplash.com/photo-1576995853123-5a10305d93c0?w=800&auto=format&fit=crop&q=80",
      variantCount: 3,
    },
  ];
}

export async function fetchProductById(id: string): Promise<ProductDetail | null> {
  try {
    const res = await fetch(`${CATALOG_DIRECT}/api/v1/products/${id}`, { cache: "no-store" });
    if (res.ok) return await res.json();
  } catch (ignored) {}

  return {
    productId: id,
    name: "Basic Premium Cotton Tee",
    slug: "basic-tee",
    description: "Áo thun nam may từ chất liệu 100% cotton chải kỹ cao cấp, thoáng mát, thấm hút mồ hôi vượt trội cùng form dáng regular trẻ trung, thanh lịch.",
    categoryName: "Áo thun nam",
    variants: [
      {
        variantId: "514a23b0-f6c0-52a2-95ca-2f5fce134d37",
        sku: "MF-TEE-BLACK-S",
        productId: id,
        productName: "Basic Premium Cotton Tee",
        price: { amount: "299000.00", currency: "VND" },
        attributes: { size: "S", color: "BLACK" },
        imageUrl: "https://images.unsplash.com/photo-1521572267360-ee0c2909d518?w=800&auto=format&fit=crop&q=80",
        active: true,
      },
      {
        variantId: "52e1d8ae-7c8f-5ec8-9ce7-a4c81caed6f5",
        sku: "MF-TEE-BLACK-M",
        productId: id,
        productName: "Basic Premium Cotton Tee",
        price: { amount: "299000.00", currency: "VND" },
        attributes: { size: "M", color: "BLACK" },
        imageUrl: "https://images.unsplash.com/photo-1521572267360-ee0c2909d518?w=800&auto=format&fit=crop&q=80",
        active: true,
      },
      {
        variantId: "ed5b314f-a04b-551c-a903-05a9ce3ad087",
        sku: "MF-TEE-WHITE-M",
        productId: id,
        productName: "Basic Premium Cotton Tee",
        price: { amount: "299000.00", currency: "VND" },
        attributes: { size: "M", color: "WHITE" },
        imageUrl: "https://images.unsplash.com/photo-1581655353564-df123a1eb820?w=800&auto=format&fit=crop&q=80",
        active: true,
      },
    ],
  };
}

export async function fetchCart(userId: string = DEMO_USER_ID): Promise<CartResponse> {
  try {
    const res = await fetch(`${CART_DIRECT}/api/v1/cart`, {
      headers: { "X-User-Id": userId },
      cache: "no-store",
    });
    if (res.ok) return await res.json();
  } catch (ignored) {}

  return {
    cartId: "demo-cart-id",
    userId,
    currency: "VND",
    items: [],
    subtotal: { amount: "0.00", currency: "VND" },
    discountAmount: { amount: "0.00", currency: "VND" },
    totalAmount: { amount: "0.00", currency: "VND" },
    updatedAt: new Date().toISOString(),
  };
}

export async function addItemToCart(
  userId: string = DEMO_USER_ID,
  payload: {
    variantId: string;
    sku: string;
    productName: string;
    size: string;
    color: string;
    imageUrl?: string;
    unitPrice: number;
    quantity: number;
  }
): Promise<CartResponse> {
  const res = await fetch(`${CART_DIRECT}/api/v1/cart/items`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-User-Id": userId,
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || "Không thể thêm sản phẩm vào giỏ hàng");
  }
  return await res.json();
}

export async function updateCartItemQuantity(
  userId: string = DEMO_USER_ID,
  variantId: string,
  quantity: number
): Promise<CartResponse> {
  const res = await fetch(`${CART_DIRECT}/api/v1/cart/items/${variantId}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      "X-User-Id": userId,
    },
    body: JSON.stringify({ quantity }),
  });
  if (!res.ok) throw new Error("Không thể cập nhật số lượng");
  return await res.json();
}

export async function removeCartItem(
  userId: string = DEMO_USER_ID,
  variantId: string
): Promise<CartResponse> {
  const res = await fetch(`${CART_DIRECT}/api/v1/cart/items/${variantId}`, {
    method: "DELETE",
    headers: { "X-User-Id": userId },
  });
  if (!res.ok) throw new Error("Không thể xóa sản phẩm khỏi giỏ hàng");
  return await res.json();
}

export async function applyCartCoupon(
  userId: string = DEMO_USER_ID,
  code: string
): Promise<CartResponse> {
  const res = await fetch(`${CART_DIRECT}/api/v1/cart/coupons`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-User-Id": userId,
    },
    body: JSON.stringify({ code }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || "Mã giảm giá không hợp lệ hoặc đã hết hạn");
  }
  return await res.json();
}
