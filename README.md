# 웹툰여지도 모바일

개인 사용을 위한 Android 앱입니다. 네이버 웹툰을 앱 안에서 탐색하고 현재 공개되어 있는 무료 회차를 기기에 저장해 오프라인으로 볼 수 있습니다.

## 구현된 흐름

1. 하단 `네이버 웹툰` 채널에서 공식 웹 페이지를 탐색합니다.
2. 작품 페이지를 열면 오른쪽 아래에 `전체 다운로드` 버튼이 나타납니다.
3. 버튼을 누르면 작품 제목, 소개, 태그, 썸네일과 공개 회차를 지정 저장소에 저장합니다.
4. 하단 `다운로드` 채널에서 저장한 작품과 회차를 선택해 세로 스크롤로 봅니다.
5. 같은 작품을 다시 다운로드하면 실제 회차 ZIP이 있는지 확인해 이미 저장된 회차는 건너뛰고 새 회차만 받습니다.
6. `설정` 채널에서 새 작품의 다운로드 폴더를 변경하거나 기본 앱 내부 저장소로 되돌릴 수 있습니다.
7. 다운로드 채널의 `삭제` 버튼은 작품 폴더 전체와 SQLite 작품·회차 정보를 함께 삭제합니다.

현재 버전은 로그인 여부와 무관하게 API에서 `thumbnailLock=false`로 표시되는 공개 회차만 대상으로 합니다. 구매·대여·성인 잠금 회차 다운로드는 구현하지 않았습니다.

## GitHub에서 APK 만들기

이 `APK` 폴더의 **내용 전체**가 GitHub 저장소 최상위에 오도록 업로드합니다. 즉 GitHub 저장소 첫 화면에서 `app`, `.github`, `build.gradle.kts`, `settings.gradle.kts`가 보여야 합니다.

1. GitHub에서 새 저장소를 만듭니다.
2. 이 폴더의 내용을 업로드하고 `main` 브랜치에 커밋합니다.
3. 저장소의 `Actions` 탭에서 `Build Android APK`를 엽니다.
4. `Run workflow`를 누릅니다. `main`에 푸시해도 자동 실행됩니다.
5. 완료된 실행 화면 아래 `Artifacts`의 `webtoon-map-apk`를 받습니다.
6. 압축을 풀어 `webtoon-map.apk`를 Android 기기에 설치합니다.

GitHub Actions는 JDK 17, Android API 36, Gradle 8.13을 준비한 뒤 디버그 서명 APK를 만듭니다. 디버그 서명키는 Actions 캐시에 유지되고 `webtoon-map-debug-signing-key` 아티팩트로도 제공됩니다. 앱 데이터가 유지되는 업데이트를 위해 이 키 아티팩트를 별도로 보관하세요. GitHub 캐시가 삭제된 뒤 새 키로 빌드하면 기존 앱을 제거해야 설치할 수 있으며, 제거 시 다운로드 데이터도 삭제됩니다.

## 프로젝트 구조

```text
app/src/main/java/com/webtoonmap/mobile/
├─ MainActivity.java                 두 채널 전환
├─ ui/NaverChannelView.java          공식 웹 페이지 WebView
├─ ui/DownloadChannelView.java       다운로드 작품 보관함
├─ ui/DownloadedSeriesActivity.java  저장 회차 목록
├─ ui/OfflineViewerActivity.java     세로 스크롤 오프라인 뷰어
├─ ui/SettingsChannelView.java       다운로드 폴더 설정
├─ naver/NaverApi.java               작품·회차·이미지 추출
├─ download/SeriesDownloadService.java 백그라운드 전체 다운로드
├─ storage/WebtoonStorage.java       내부·사용자 선택 폴더 저장
└─ data/LibraryDatabase.java         SQLite 메타데이터
```

기본 다운로드 파일은 Android 앱 전용 내부 저장소의 `files/webtoons/{titleId}` 아래에 있습니다. 설정에서 폴더를 선택하면 새 작품은 선택한 폴더의 `{titleId}` 아래에 저장됩니다. 작품별 위치를 DB에 따로 기록하므로 이후 설정을 변경해도 기존 작품은 원래 위치에서 열리고 삭제됩니다.

```text
{저장소}/{titleId}/
├─ thumbnail.jpg
├─ 001.zip
├─ 002.zip
└─ ...
```

각 ZIP에는 `001.jpg`, `002.jpg` 형식의 회차 이미지가 들어갑니다. 뷰어는 선택한 ZIP만 앱 캐시에 임시로 풀어 표시하고 뷰어 종료 시 캐시를 삭제합니다. 기본 내부 저장소는 앱 제거 시 함께 삭제되지만 사용자가 선택한 외부 폴더는 앱 제거만으로 삭제되지 않으므로, 앱의 작품 삭제 버튼을 사용해야 원본 폴더까지 지워집니다.

## 알려진 제약

- 네이버의 공개 웹/API 구조가 바뀌면 `NaverApi.java`의 파싱 로직을 수정해야 할 수 있습니다.
- 한 번에 한 작품만 다운로드합니다.
- 앱 데이터 삭제나 앱 제거 시 내려받은 작품도 삭제됩니다.
- Android 버전에 따라 저장소 최상위나 `Download` 최상위 대신 그 안에 만든 하위 폴더를 선택해야 할 수 있습니다.
