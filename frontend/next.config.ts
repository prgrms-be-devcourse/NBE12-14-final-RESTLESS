import type { NextConfig } from "next";

// 백엔드(Spring Boot)에 CORS 설정이 없으므로 /api/* 요청을 Next 서버가 대신 전달한다.
// 브라우저 입장에서는 같은 출처라 Refresh 쿠키(Path=/api/auth)도 그대로 오간다.
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // 데모 화면을 가리지 않도록 개발 표시 배지를 끈다
  devIndicators: false,
  // 경매 SSE 스트림은 최대 30분 유지되므로 프록시 제한을 그보다 길게 둔다.
  experimental: {
    proxyTimeout: 31 * 60 * 1000,
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }];
  },
};

export default nextConfig;
