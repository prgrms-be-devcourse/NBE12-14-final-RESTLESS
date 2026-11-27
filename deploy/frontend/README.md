# 프론트 S3 + CloudFront 자동 배포

프론트는 Next 정적 내보내기(`output: "export"`)로 `frontend/out/`을 만들고 S3에 올린다.
CloudFront 하나가 화면은 S3에서, `/api/*`는 백엔드 ALB에서 받아 같은 출처를 유지한다. 백엔드에 CORS를 두지 않고 Refresh 쿠키(`Path=/api/auth`, `SameSite=Strict`)도 그대로 쓴다.

dev의 CI가 성공하면 [frontend-deploy 워크플로](../../.github/workflows/frontend-deploy.yml)가 그 커밋을 다시 빌드해서 올린다.
GitHub에는 AWS 키를 두지 않는다. 워크플로가 OIDC로 배포 역할을 받는다.

## 동작

1. 테스트(`tests/*.cjs`)와 빌드가 통과해야 올린다.
2. 그 커밋이 아직 dev의 최신일 때만 올린다. 늦게 끝난 옛 커밋이 최신 화면을 덮어쓰지 않는다.
3. `_next/static`은 1년 캐시하고 지우지 않는다. 화면(html)과 화면 데이터(txt)는 매번 다시 확인하고, 이번 빌드에 없는 파일은 지운다.
4. CloudFront 캐시를 `/*`로 무효화한다.

`FRONTEND_BUCKET` 변수가 비어 있으면 작업을 건너뛴다. 지금 배포를 다시 하려면 Actions에서 Frontend deploy를 dev로 실행한다.

## 처음 설정 (콘솔)

리전은 ap-northeast-2다.

1. S3 버킷: 퍼블릭 액세스 차단 모두 켬, ACL 비활성화, SSE-S3. 정적 웹사이트 호스팅은 켜지 않는다.
2. CloudFront Function: 런타임 cloudfront-js-2.0, 코드는 [cloudfront-rewrite.js](cloudfront-rewrite.js). 게시까지 한다. `/drops/detail` 같은 주소를 `drops/detail.html`로 바꾼다.
3. CloudFront 배포
   - 기본 동작: S3 원본(REST 주소)과 OAC, Redirect HTTP to HTTPS, CachingOptimized, 뷰어 요청에 위 함수. 기본 루트 객체 `index.html`.
   - `/api/*` 동작: ALB 원본(HTTP 80, 응답 제한 60초), 메서드 전체, CachingDisabled, AllViewerExceptHostHeader. 함수 없음.
   - 사용자 정의 오류 응답은 만들지 않는다. 배포 전체에 적용돼 `/api`의 403, 404 JSON까지 HTML로 바뀐다.
   - ALB 보안 그룹 인바운드 80에 `com.amazonaws.global.cloudfront.origin-facing` 접두사 목록을 허용한다.
4. 버킷 정책: CloudFront가 주는 OAC 정책을 넣는다. `s3:ListBucket`도 허용하면 없는 주소가 403 대신 404가 된다.
5. IAM > 자격 증명 공급자: `token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com`. 이미 있으면 그대로 쓴다.
6. IAM 역할: 신뢰 정책 [deploy-role-trust.json](deploy-role-trust.json), 권한 [deploy-role-policy.json](deploy-role-policy.json). `<계정 ID>`, `<버킷 이름>`, `<배포 ID>`를 채운다.
7. GitHub 저장소 변수(Settings > Secrets and variables > Actions > Variables)

| 변수 | 값 |
|---|---|
| `FRONTEND_DEPLOY_ROLE_ARN` | 6번 역할의 ARN |
| `FRONTEND_BUCKET` | 1번 버킷 이름 |
| `FRONTEND_DISTRIBUTION_ID` | 3번 배포 ID |
| `TOSS_CLIENT_KEY` | 토스 테스트 클라이언트 키. 브라우저에 공개되는 값이다. 백엔드 시크릿 키와 짝이어야 한다 |

## 확인

| 할 일 | 방법 |
|---|---|
| 배포 기록 | Actions > Frontend deploy, 실행 요약에 커밋과 무효화 ID |
| 배포를 건너뛴 이유 | 실행 로그의 notice. dev가 이미 다음 커밋이면 건너뛴다 |
| 화면 확인 | `https://<배포 도메인>/drops/detail?id=1` |
| `/api` 전달 확인 | `https://<배포 도메인>/api/drops` |
