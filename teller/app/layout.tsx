import "./globals.css";
export const metadata = { title:"Teller — Notify installs", description:"Device registration & install tracking for Notify" };
export default function RootLayout({children}:{children:React.ReactNode}){
  return <html lang="en"><body className="min-h-screen bg-white text-ink antialiased">{children}</body></html>
}
