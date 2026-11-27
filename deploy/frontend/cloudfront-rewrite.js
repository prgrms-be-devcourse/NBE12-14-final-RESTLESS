// CloudFront Function (cloudfront-js-2.0), viewer request, 기본 동작(S3)에만 연결한다. /api/*에는 붙이지 않는다.
// Next 정적 내보내기는 /drops/detail 화면을 drops/detail.html로 만든다. 확장자 없는 주소에 .html을 붙인다.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  if (uri === "/") {
    request.uri = "/index.html";
  } else if (uri.endsWith("/")) {
    request.uri = uri.slice(0, -1) + ".html";
  } else if (uri.split("/").pop().indexOf(".") === -1) {
    request.uri = uri + ".html";
  }
  return request;
}
