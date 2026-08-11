package kr.kennysoft.ledgermemo.journal

/**
 * 분개장 한 행.
 *
 * 컬럼 구성은 본 가계부 "분개장" 탭과 같다. TX#/차변/대변/검증은 시트에서 채우는 값이라
 * 여기서 만들지 않는다 (차/대변은 행번호를 참조하는 수식이고, TX# 는 사용자가 부여한다).
 */
data class JournalRow(
    /** `비용의 발생 | 대변` 처럼 "분류 | 차대" 형태. */
    val item: String,
    val amount: Int,
    /** `해당 없음` / `신용카드` / `직불카드 등` 등. 비용 행은 언제나 `해당 없음`. */
    val incomeRelated: String,
    val title: String,
    /** 결제 행에만 채운다 (비용 행은 빈칸). */
    val paymentMethod: String,
    /** 결제 행에만 채운다. 보통 가맹점. */
    val payee: String,
    /** `비용: 식비` / `카드: ...` / `계좌: ...`. 확정할 수 없으면 `?` 를 남긴다. */
    val account: String,
    val memo: String,
)

/**
 * 분개 미리보기. 시트에 붙여넣을 형태를 그대로 보여준다.
 *
 * [needsAttention] 은 사용자가 손으로 채워야 하는 자리가 남았는지다. 계정에 `?` 가 있으면
 * 그대로 옮길 수 없으니 화면에서 눈에 띄게 한다.
 */
data class JournalPreview(
    /** `2026-08-09` — 시트가 날짜로 파싱하는 형식. */
    val date: String,
    /** `21:37`. 시각을 모르면 빈 문자열. */
    val time: String,
    val rows: List<JournalRow>,
    val debitTotal: Int,
    val creditTotal: Int,
    val needsAttention: Boolean,
)
