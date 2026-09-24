import Link from "next/link";
import Layout from "@/components/Layout";

export default function NotFound() {
  return <Layout title="없는 페이지"><div className="py-20 text-center"><h1 className="text-lg font-semibold">페이지를 찾을 수 없습니다</h1>
    <Link href="/" className="text-accent hover:underline text-sm">대시보드로</Link></div></Layout>;
}
