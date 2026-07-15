# 텔레그램 모바일 활성화 설정

## GitHub Actions 비밀값

저장소에서 `Settings → Secrets and variables → Actions → New repository secret`로 이동해 다음 두 값을 등록합니다.

- `TELEGRAM_BOT_TOKEN`: 원본 프로그램에서 사용하던 기존 텔레그램 봇 토큰
- `TELEGRAM_ADMIN_CHAT_ID`: 원본 프로그램에서 사용하던 기존 관리자 채팅 ID

두 값 중 하나라도 없으면 `Validate Telegram activation secrets` 단계에서 APK 빌드가 중단됩니다. 값 자체는 저장소 파일이나 Actions 로그에 출력하지 않습니다.

## 최초 활성화 순서

1. 기존 PC 원본 프로그램을 종료합니다. 같은 봇을 동시에 폴링하면 한쪽이 명령을 먼저 받을 수 있습니다.
2. 새 APK를 설치하고 처음 실행합니다.
3. 사용자명을 입력하고 저장합니다. 저장한 사용자명은 앱 데이터를 지우기 전까지 변경할 수 없습니다.
4. 화면의 명령에서 `비번`을 원하는 암호로 바꿔 관리자 텔레그램 채팅에 보냅니다.

```text
/웹툰모바일 사용자명 (비번)
```

5. 앱에 활성화 암호 입력란이 나타나면 괄호 안에 보낸 암호를 똑같이 입력합니다.
6. 활성화가 완료되면 메인 웹툰 화면이 열립니다.

활성화 정보는 앱 전용 설정에 저장됩니다. 같은 서명키로 만든 APK를 덮어써서 업데이트하면 유지되며, 앱 제거 또는 앱 데이터 삭제 시 사라집니다.

## GitHub 저장소 비공개 전환

저장소의 `Settings → General → Danger Zone → Change repository visibility`에서 `Private`를 선택해 확인합니다. 이미 공개 상태로 커밋했던 비밀값은 저장소를 비공개로 바꿔도 노출된 것으로 취급해야 하므로, 봇 토큰은 저장소 파일에 직접 넣지 말고 위 Repository secret으로만 등록합니다.
