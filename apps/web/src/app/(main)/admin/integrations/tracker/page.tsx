import type { Metadata } from "next";
import { AdminTrackerPage } from "@views/admin-tracker";
export const metadata: Metadata = { title: "배송 Tracker 연동" };
export default function Page() {
  return <AdminTrackerPage />;
}
