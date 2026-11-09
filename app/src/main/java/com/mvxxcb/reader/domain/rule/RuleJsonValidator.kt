package com.mvxxcb.reader.domain.rule

interface RuleJsonValidator {
    fun validate(ruleJson: String): com.mvxxcb.reader.common.result.AppResult<Unit>   // 失败为 ReaderError.State(INVALID_ARGUMENT)
}
