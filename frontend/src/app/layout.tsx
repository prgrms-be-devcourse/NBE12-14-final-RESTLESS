import type { Metadata, Viewport } from "next";
import Providers from "@/components/Providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "FANQ | 공연 예매, 한정판 드롭, 팬미팅, 경매",
  description: "공연 예매, 한정판 드롭, 라이브 경매, 팬미팅 추첨까지. 팬덤 이벤트 커머스 FANQ",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#04050C" },
    { media: "(prefers-color-scheme: dark)", color: "#04050C" },
  ],
};

// 저장된 테마를 첫 페인트 전에 적용해 깜빡임을 막는다
// 기본은 우주(다크) 테마. 사용자가 새벽(라이트)을 고르면 그 값을 쓴다.
const themeBoot = `(function(){var t='dark';try{if(localStorage.getItem('fanq.theme')==='light')t='light'}catch(e){}document.documentElement.setAttribute('data-theme',t)})()`;

// 인트로 연출 설정도 첫 페인트 전에 표시해, 꺼 둔 사람에게 연출이 잠깐 보이지 않게 한다. 키는 src/lib/intro.tsx와 같다
const introBoot = `(function(){var v='on';try{var s=localStorage.getItem('fanq.intro');if(s==='on'||s==='off')v=s;else if(matchMedia('(prefers-reduced-motion: reduce)').matches)v='off'}catch(e){}document.documentElement.setAttribute('data-intro',v)})()`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeBoot }} />
        <script dangerouslySetInnerHTML={{ __html: introBoot }} />
        <link
          rel="stylesheet"
          href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css"
        />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
