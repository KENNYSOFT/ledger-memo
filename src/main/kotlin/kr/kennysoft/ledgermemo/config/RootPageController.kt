package kr.kennysoft.ledgermemo.config

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * 루트 요청을 index.html 로 넘긴다.
 *
 * Spring Boot 의 welcome page 자동 매핑은 **빌드 시점에 classpath 의 `static/index.html` 을
 * 잡는다.** native image 에서는 그 결정이 그대로 굳어, 볼륨에 새 index.html 을 올려도 옛
 * 화면이 나온다. forward 로 넘기면 리소스 핸들러가 요청 시점에
 * `spring.web.resources.static-locations` 순서대로 찾으므로 볼륨의 파일이 반영된다.
 */
@Controller
class RootPageController {

    @GetMapping("/")
    fun index(): String = "forward:/index.html"
}
