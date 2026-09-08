import type { Metadata } from "next";
import { AdminPromotionPostsView } from "@views/admin";

export const metadata: Metadata = { title: "홍보 게시" };

export default function Page() {
  return <AdminPromotionPostsView />;
}
