# Md-docx-to-hwp

**Md-docx-to-hwp**는 Markdown과 DOCX를 바이너리 HWP 5 문서로 변환하는 로컬 도구예요.
Markdown·DOCX → HWP 단방향 변환에 집중하며, HWP → DOCX 변환은 개발 범위에 포함하지 않아요.
Python으로 입력을 읽고 공개 Java 라이브러리 hwplib로 HWP를 작성해요.
Polaris Office의 원본 코드·APK·네이티브 라이브러리는 포함하지 않아요.

## 설치 및 실행

Python 3.10 이상과 JDK 17 이상이 필요해요. Python 환경에 의존성을 설치하세요.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
export HWP_CONVERTER_JAVA_HOME="/absolute/path/to/jdk"
python convert.py input.docx -o output.hwp --json
python convert.py input.md -o output.hwp --json
```

JDK의 `bin/javac`가 PATH에 있으면 JDK 경로 설정을 생략할 수 있어요.
`hwpconvert` 실행 파일은 최초 개발 환경의 Python 경로를 기본값으로 사용해요.
다른 환경에서는 `python convert.py`를 실행하거나
`HWP_CONVERTER_PYTHON`을 사용할 Python 실행 파일의 절대 경로로 지정하세요.

Java 의존성은 `vendor/`에 포함되어 있어요. 버전과 SHA-256은
`vendor/dependency-lock.json`에 기록되어 있어요.

## 지원 범위

- 한글·Unicode 본문, 제목, 기본 글자 서식, 문단 순서, 명시적 페이지 나눔
- Markdown 목록과 DOCX 목록의 표시 텍스트
- DOCX 페이지 크기·여백, 문단 전후 간격·줄 간격·들여쓰기
- 표 열 너비와 가로·세로 셀 병합
- DOCX 각주·머리말·꼬리말·PAGE 페이지 번호 필드, 클릭 가능한 외부 링크
- 위아래첨자, HWP 보고서 템플릿
- 포함 이미지와 로컬 이미지의 PNG 정규화 및 HWP 삽입
- HWP 재읽기 검증 후 결과 파일 저장

출력 파일이 이미 있으면 기본적으로 덮어쓰지 않아요.
`--force`는 명시적 덮어쓰기, `--strict`는 지원 범위 경고가 있을 때 저장 중단 옵션이에요.

## 한계

- 표 배경·테두리·셀 안쪽 여백, 미주·수식·텍스트 상자, 여러 구역과 첫 페이지/홀짝 머리말은 완전히 보존하지 않아요.
- PAGE 외의 필드는 저장된 표시 텍스트만 유지하며, 목록은 번호/기호 텍스트로 변환해요.
- 원본과 동일한 페이지 나눔을 보장하지 않아요.

## 보고서 템플릿

```sh
python convert.py report.docx -o report.hwp --reference-doc report-template.hwp --json
```

- 단일 구역의 **글자·스타일만 있는 HWP**를 기준으로 사용해요.
- 템플릿의 페이지 설정과 본문·제목 스타일이 원본의 페이지/문단 서식보다 우선해요.
- 원본 내용과 굵게·기울임 등 강조는 유지하고, 템플릿 기존 본문은 복사하지 않아요.
- 제목 스타일은 Heading/제목/개요와 단계 번호로 매핑해요.
- 이미지가 포함된 템플릿은 오류로 중단해 출력 파일을 보호해요. 템플릿 머리말·꼬리말·로고는 복제하지 않아요.

## 검증

```sh
python -m unittest discover -s tests -v
python -m compileall -q convert.py converter tests
```

- 입력·실제 변환 테스트 20개로 한글·이모지·표·병합·이미지·본문 순서, 출력 보존·덮어쓰기·strict 실패 처리 등을 검사해요.
- HWP OLE 스트림과 압축 레코드를 별도로 검사하는 테스트도 포함되어 있어요.
- Hancom Office HWP에서 샘플 및 6쪽 DOCX 보고서의 표시를 확인했어요.
- 편집·저장 동작과 원본 레이아웃의 완전한 재현을 검증한 것은 아니에요.

## 구조 및 외부 라이브러리

- `converter/inputs.py`: 입력 문서를 공통 구조로 읽기
- `convert.py`: CLI, Java 작성기 실행, 출력 검증, 안전한 저장
- `java/HwpWriterBridge.java`: HWP 레코드 작성
- `tests/`: 입력·실제 변환 테스트
- `examples/`: 합성 테스트 샘플
- `vendor/`: hwplib 1.1.11, Gson 2.13.2, Error Prone annotations 2.38.0

Java 외부 라이브러리는 Apache-2.0 라이선스이며 전문은
[vendor/APACHE-2.0.txt](vendor/APACHE-2.0.txt)에 있어요.
hwplib 프로젝트: https://github.com/neolord0/hwplib
