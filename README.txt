Md-docx-to-hwp
2026-10-14 / macbook-15

Markdown(.md, .markdown)와 DOCX를 바이너리 HWP 5 문서로 변환한다.
단방향 변환에 집중하며 HWP → DOCX 변환은 개발 범위에 포함하지 않는다.
파일 변환은 로컬에서 실행되며, 원격 이미지나 문서 업로드를 하지 않는다.
출력 파일을 다시 읽어 확인한 뒤 최종 파일로 저장한다.

실행
  cd "/Users/dannygu/Documents/ChatGPT/macbook 15/md-docx-to-hwp"
  ./hwpconvert "/경로/문서.md" -o "/경로/문서.hwp"
  ./hwpconvert "/경로/문서.docx" -o "/경로/문서.hwp"

- -o 생략: 입력과 같은 폴더, 같은 이름의 .hwp 생성.
- --force: 기존 출력 파일을 덮어쓴다. 기본 동작은 기존 출력 보존.
- --strict: 지원 범위 경고가 하나라도 있으면 결과를 저장하지 않는다.
- --json: 문단·표·이미지 개수, 경고 및 출력 경로를 JSON으로 표시한다.
- 파일 이름에 공백이 있으면 경로를 따옴표로 감싼다.
- 현재 설치 위치에서 인터넷 없이 재실행할 수 있다.

지원 범위
- 한글·영문·Unicode 본문, 문단 순서, 제목, 정렬, 명시적 페이지 나눔.
- 굵게·기울임·밑줄·취소선, 글자 크기·색상·글꼴, 코드 텍스트.
- Markdown 번호/기호/체크 목록, DOCX 일반 목록의 표시 텍스트.
  목록은 HWP 번호 매기기 기능이 아니라 번호·기호 텍스트로 변환한다.
- Markdown 표와 DOCX 표, 기본 가로·세로 셀 병합.
- 로컬 Markdown 이미지와 DOCX 포함 이미지. PNG로 정규화하고 HWP에 포함한다.
  이미지는 인라인 도형의 그림 채우기로 저장한다.

보고서 서식 및 템플릿
- DOCX 페이지 크기·여백, 문단 간격·줄 간격·들여쓰기, 실제 표 열 너비 보존.
- DOCX 각주·머리말·꼬리말과 동적 PAGE 번호, 외부 링크, 위아래첨자 지원.
- --reference-doc /경로/양식.hwp: 기준 HWP 페이지 설정과 본문/제목 스타일 적용.
  원본 내용과 강조 유지. 템플릿 기존 본문·머리말·꼬리말은 복사하지 않는다.
  단일 구역의 텍스트/스타일 템플릿만 지원하며 포함 이미지가 있으면 중단한다.

지원 한계와 경고
- 표 배경·테두리·셀 안쪽 여백·정밀 배치 및 동일 페이지 나눔은 보장하지 않는다.
- 여러 구역, 첫 페이지/홀짝 머리말 변형은 보존하지 않는다.
- 미주·수식·텍스트 상자, PAGE 외의 동적 필드는 지원 범위가 제한된다.
- 목록은 자동 번호 기능 대신 표시 텍스트로 변환한다.
- 셀 내부 그림·중첩 표와 부동 그림 위치는 평탄화한다.
- HTTP 이미지 URL은 가져오지 않고 경고와 대체 텍스트를 남긴다.

구조와 출처
- converter/inputs.py: Markdown·DOCX를 공통 문서 구조로 읽는다.
- convert.py: 임시 작업, Java 컴파일, HWP 작성, 검증, 안전한 출력 저장.
- java/HwpWriterBridge.java: 문서 구조를 공개 hwplib API로 HWP 레코드에 매핑.
- hwpconvert: 이 맥에서 실행하기 위한 명령 파일.
- vendor/: hwplib 1.1.11, Gson 2.13.2, Error Prone annotations 2.38.0,
  mistune 3.1.3 및 독립 검증용 olefile 0.47. 버전과 해시는 dependency-lock.json에 기록.
- Python의 python-docx 및 Pillow는 Codex 제공 런타임을 사용한다.
- REA 조사에서 Polaris Java 계층이 ARM32 네이티브 엔진에 연결됨을 확인했다.
  이 도구의 HWP 작성 구현에는 Polaris 코드·APK·.so를 포함하거나 실행하지 않는다.
  REA 조사는 엔진 경계를 이해하는 근거로 사용했고, 실제 출력 코드는 별도로 작성했다.
- 공개 작성 라이브러리: https://github.com/neolord0/hwplib (Apache-2.0)
- 이미지 구성 참고: hwplib/src/test/sample/Inserting_Image.java
  Apache-2.0 전문: vendor/APACHE-2.0.txt

검증
  /Users/dannygu/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 \
    -m unittest discover -s tests -v

입력 및 실제 변환 테스트 20개 통과: 한글·이모지·서식·본문 순서·표·병합·포함 이미지,
출력 파일 보존, 명시적 덮어쓰기, strict 경고 처리, 빈 문서, 잘못된 경로/확장자.
olefile로 OLE 스트림을 읽고 압축된 DocInfo/BodyText HWP 레코드를 독립 검사했다.
Java -Xlint:all -Werror 및 Python 컴파일 검사를 수행했다.
Hancom Office HWP 12.30.0 앱에서 Markdown 샘플과 DOCX 이미지·병합 표 샘플을 표시 확인했다.
앱 검증은 오프라인 읽기 전용 모드에서 수행했으며 저장·편집 동작은 검증하지 않았다.

검증한 샘플
  examples/sample-verified.hwp
  examples/docx-verified.hwp
