package com.restless.member.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 프로필 수정 요청. 보낸 필드만 바꾼다.
 * <ul>
 *   <li>필드를 빼거나 null: 그대로 둔다.</li>
 *   <li>기본 배송지 필드에 빈 문자열(""): 그 값을 지운다.</li>
 *   <li>닉네임은 지울 수 없다.</li>
 * </ul>
 * 이메일, 역할은 이 요청으로 바꿀 수 없다(필드가 없어서 보내도 무시된다).
 */
public record ProfileUpdateRequest(
        @Size(min = 2, max = 30) @Pattern(regexp = ".*\\S.*", message = "공백만으로는 쓸 수 없습니다.") String nickname,
        @Size(max = 50) String defaultRecipient,
        @Size(max = 20) @Pattern(regexp = "^[0-9-]*$", message = "숫자와 -만 쓸 수 있습니다.") String defaultPhone,
        @Size(max = 255) String defaultAddress) {
}
