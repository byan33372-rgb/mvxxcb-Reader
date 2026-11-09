package com.mvxxcb.reader.service

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.rule.RuleJsonValidator
import com.mvxxcb.reader.parser.rule.RuleEngine

/**
 * 规则 JSON 校验门面（契约 02 §5.4）：委托 parser 层 [RuleEngine.parseRuleJson]。
 * 装配层实现（01 §3：service/ = 主 Agent 装配层）。
 */
class RuleJsonValidatorImpl(
    private val ruleEngine: RuleEngine,
) : RuleJsonValidator {

    override fun validate(ruleJson: String): AppResult<Unit> =
        when (val result = ruleEngine.parseRuleJson(ruleJson)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
}
