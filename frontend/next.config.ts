import type { NextConfig } from "next";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants";

// 백엔드(Spring Boot)에 CORS 설정이 없으므로 개발 서버에서는 /api/* 요청을 Next 서버가 대신 전달한다.
// 브라우저 입장에서는 같은 출처라 Refresh 쿠키(Path=/api/auth)도 그대로 오간다.
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export default function config(phase: string): NextConfig {
  if (phase === PHASE_DEVELOPMENT_SERVER) {
    return {
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
  }
  // 빌드 결과는 S3에 올리는 정적 파일이다 (out/). Next 서버가 없으므로 rewrites를 쓸 수 없고,
  // 배포 환경에서는 CloudFront가 /api/*를 백엔드로 보내 같은 출처를 유지한다.
  return {
    devIndicators: false,
    output: "export",
  };
}
