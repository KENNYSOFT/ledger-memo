package kr.kennysoft.ledgermemo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LedgerMemoApplication

fun main(args: Array<String>) {
    runApplication<LedgerMemoApplication>(*args)
}
