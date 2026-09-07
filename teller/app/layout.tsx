import "./globals.css";
export const metadata = { title:"Teller — Uncry installs", description:"Device registration & install tracking for Uncry" };
export default function RootLayout({children}:{children:React.ReactNode}){
  return <html lang="en"><body className="min-h-screen bg-white text-ink antialiased">{children}</body></html>
}
