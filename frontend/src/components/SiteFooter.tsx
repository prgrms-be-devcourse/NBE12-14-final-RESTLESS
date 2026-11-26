import Link from "next/link";
import "./SiteFooter.css";

const LINKS = [
  { href: "/concerts", label: "공연 예매" },
  { href: "/drops", label: "한정판 드롭" },
  { href: "/fan-meetings", label: "팬미팅 추첨" },
  { href: "/auctions", label: "라이브 경매" },
  { href: "/me", label: "마이페이지" },
];

/** 모든 본 화면 아래에 붙는 바닥글. 큰 외곽선 글자 하나와 바로가기만 둔다 */
export default function SiteFooter() {
  return (
    <footer className="fq-foot">
      <div className="fq-foot-top">
        <p>
          팬덤 이벤트 커머스 FANQ
          <br />
          공연 예매, 한정판 드롭, 팬미팅 추첨, 라이브 경매
        </p>
        <nav aria-label="바닥글 바로가기">
          {LINKS.map((l) => (
            <Link key={l.href} href={l.href}>
              {l.label}
            </Link>
          ))}
        </nav>
      </div>
      <div className="fq-foot-word" aria-hidden="true">
        FANQ
      </div>
      <div className="fq-foot-base">
        <span>FANQ LIVE SPACE</span>
        <span>팀 RESTLESS</span>
      </div>
    </footer>
  );
}
