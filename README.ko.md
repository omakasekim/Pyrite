# Pyrite

Kotlin과 ASM으로 구현한 검증 스택 바이트코드 VM 및 Brainfuck→JVM 바이트코드 JIT 컴파일러입니다. 검증기(Verifier), 반복 핍홀 최적화기(`VmOptimizer`), 연료 계량(fuel metering), 기본 블록 제어흐름 그래프 내보내기(`CfgExporter`), JVM 메서드 크기 제한 자동 분할을 포함합니다.

[English Documentation](README.md)

## 주요 기능 (`v0.3.0`)

- **검증 스택 VM & JVM JIT**: 모든 명령어의 스택 깊이·함수 경계·인자 수를 정적으로 검증한 뒤 인터프리터 또는 숨겨진 JVM 정적 메서드(ASM JIT)로 실행하며, 무작위 프로그램 차등 테스트로 두 백엔드의 일치를 검증합니다.
- **최적화기 (`VmOptimizer`)**: 상수 폴딩(`push+dup` 포함), 상수 분기 제거, 항등식·상쇄 쌍(`dup; and`, `dup; or`), 강도 절감(`push -1; mul -> neg`), 로컬 포워딩(`store k; load k -> dup; store k`), 점프 스레딩, 미도달 코드 및 미호출 함수 제거.
- **CFG & 콜 그래프 내보내기 (`pyrite cfg`)**: 검증된 기본 블록·스택 깊이·분기/호출 간선을 Graphviz DOT, Mermaid, JSON으로 내보냅니다.
- **Brainfuck 컴파일러**: 핍홀 최적화, 스레드 인터프리터, JVM JIT 컴파일 및 연료 제한 지원.

## 빌드 및 테스트

```sh
./gradlew --no-daemon build
./gradlew --no-daemon :installDist
build/install/pyrite/bin/pyrite cfg examples/fib.asm --optimize --format mermaid
```

## 라이선스

MIT License. 세부 사항은 [LICENSE](LICENSE) 파일을 참조하십시오.
