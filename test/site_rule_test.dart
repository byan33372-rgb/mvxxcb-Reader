// 站点规则模型/Schema 校验/草稿编解码测试（对齐 Kotlin RuleJsonValidatorImpl）。
import 'package:flutter_test/flutter_test.dart';

import 'package:jianyue_flutter/core/reader_error.dart';
import 'package:jianyue_flutter/core/site_rule.dart';

void main() {
  group('parseRuleSchema', () {
    test('合法完整规则通过', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "encoding": "GBK",
          "bookNameSelector": "h1.title",
          "chapterLinkSelector": "dd a",
          "contentSelector": "#content",
          "contentCleaningRules": [
            {"type": "regex", "pattern": "请收藏本站.*"}
          ]
        }
      ''');
      expect(result.isValid, isTrue);
      expect(result.rule!.ruleId, 'demo.v1');
      expect(result.rule!.domain, 'www.example.com');
      expect(result.rule!.encoding, 'GBK');
      expect(result.rule!.selectors.length, 3);
      expect(result.rule!.cleaningRules.length, 1);
      expect(result.rule!.isManual, isFalse);
    });

    test('缺必填字段回显 fieldErrors', () {
      final result = parseRuleSchema('{"domain": "www.example.com"}');
      expect(result.rule, isNull);
      expect(result.fieldErrors.containsKey('ruleId'), isTrue);
      expect(result.fieldErrors.containsKey('version'), isTrue);
    });

    test('非法域名/版本范围/编码白名单', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "not-a-domain",
          "version": 0,
          "encoding": "UTF-16"
        }
      ''');
      expect(result.fieldErrors['domain'], contains('域名'));
      expect(result.fieldErrors['version'], contains('1..999999'));
      expect(result.fieldErrors['encoding'], contains('UTF-8/GBK'));
    });

    test('manual.* ruleId 命名与 isManual 标记', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "manual.www.example.com",
          "domain": "www.example.com",
          "version": 1
        }
      ''');
      expect(result.isValid, isTrue);
      expect(result.rule!.isManual, isTrue);
    });

    test('非法 manual ruleId 拒绝', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "manual.a",
          "domain": "www.example.com",
          "version": 1
        }
      ''');
      expect(result.fieldErrors.containsKey('ruleId'), isTrue);
    });

    test('CSS 简写 @attr 后缀解析', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "coverSelector": "img.cover@src"
        }
      ''');
      expect(result.isValid, isTrue);
      final spec = result.rule!.selectors[RuleSelectorKey.cover]!;
      expect(spec, isA<CssSpec>());
      final css = spec as CssSpec;
      expect(css.expr, 'img.cover');
      expect(css.attr, 'src');
    });

    test('未知 @attr 当作文本（不报错）', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "bookNameSelector": "h1@unknown-attr"
        }
      ''');
      expect(result.isValid, isTrue);
      final spec = result.rule!.selectors[RuleSelectorKey.bookName] as CssSpec;
      expect(spec.expr, 'h1@unknown-attr');
      expect(spec.attr, 'text');
    });

    test('对象形态选择器（css/xpath/regex/jsonpath）', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "bookNameSelector": {"type": "css", "expr": "h1", "attr": "text"},
          "authorSelector": {"type": "regex", "expr": "作者：(\\\\w+)", "group": 1},
          "coverSelector": {"type": "jsonpath", "expr": "data.cover"}
        }
      ''');
      expect(result.isValid, isTrue);
      final selectors = result.rule!.selectors;
      expect(selectors[RuleSelectorKey.bookName], isA<CssSpec>());
      expect(selectors[RuleSelectorKey.author], isA<RegexSpec>());
      expect(selectors[RuleSelectorKey.cover], isA<JsonPathSpec>());
    });

    test('xpath 一期未启用但解析通过（执行期报 Miss）', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "bookNameSelector": {"type": "xpath", "expr": "//h1"}
        }
      ''');
      expect(result.isValid, isTrue);
      expect(result.rule!.selectors[RuleSelectorKey.bookName], isA<XpathSpec>());
    });

    test('复杂度上限：regex 长度 / jsonpath 深度', () {
      final longRegex = 'a' * 201;
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "bookNameSelector": {"type": "regex", "expr": "$longRegex"},
          "coverSelector": {"type": "jsonpath", "expr": "a.b.c.d.e.f.g.h.i.j"}
        }
      ''');
      expect(result.fieldErrors['bookNameSelector'], contains('regex 长度'));
      expect(result.fieldErrors['coverSelector'], contains('jsonpath 深度'));
    });

    test('非法 regex 回显语法错误', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "bookNameSelector": {"type": "regex", "expr": "([unclosed"}
        }
      ''');
      expect(result.fieldErrors['bookNameSelector'], contains('regex'));
    });

    test('清洗规则类型与长度校验', () {
      final result = parseRuleSchema('''
        {
          "ruleId": "demo.v1",
          "domain": "www.example.com",
          "version": 1,
          "contentCleaningRules": [
            {"type": "unknown", "pattern": "x"},
            {"type": "regex", "pattern": ""}
          ]
        }
      ''');
      expect(result.fieldErrors['contentCleaningRules[0].type'], isNotNull);
      expect(result.fieldErrors['contentCleaningRules[1].pattern'], isNotNull);
    });

    test('损坏 JSON 单条 fieldErrors', () {
      final result = parseRuleSchema('{not json');
      expect(result.rule, isNull);
      expect(result.fieldErrors['ruleJson'], contains('JSON'));
    });
  });

  group('jsonPathDepth', () {
    test('按执行器遍历步数计（b 与 [0] 为两步）', () {
      expect(jsonPathDepth('a.b.c'), 3);
      expect(jsonPathDepth('a.b[0].c'), 4);
      expect(jsonPathDepth('[0].a'), 2);
      expect(jsonPathDepth('a'), 1);
    });
  });

  group('xpathDepth', () {
    test('按 / 分段', () {
      expect(xpathDepth('//div/span'), 2);
      expect(xpathDepth('/html/body/div'), 3);
    });
  });

  group('RuleDraft 编解码', () {
    test('encode → decode 往返（regex 清洗保留）', () {
      const draft = RuleDraft(
        domain: 'www.example.com',
        encoding: 'GBK',
        bookNameSelector: 'h1.title',
        chapterLinkSelector: 'dd a',
        contentSelector: '#content',
        cleaningRulesText: '请收藏本站.*\n一秒记住.*',
      );
      final json = encodeRuleDraft(draft);
      expect(json.contains('manual.www.example.com'), isTrue);

      final back = decodeRuleDraft(json)!;
      expect(back.domain, 'www.example.com');
      expect(back.encoding, 'GBK');
      expect(back.bookNameSelector, 'h1.title');
      expect(back.chapterLinkSelector, 'dd a');
      expect(back.contentSelector, '#content');
      expect(back.cleaningRulesText, '请收藏本站.*\n一秒记住.*');
    });

    test('encode 后 Schema 校验通过', () {
      const draft = RuleDraft(
        domain: 'www.example.com',
        bookNameSelector: 'h1',
        chapterLinkSelector: 'a',
      );
      final result = parseRuleSchema(encodeRuleDraft(draft));
      expect(result.isValid, isTrue);
      expect(result.rule!.isManual, isTrue);
    });

    test('decode 空选择器与损坏 JSON', () {
      expect(decodeRuleDraft('not json'), isNull);
      final back = decodeRuleDraft('{"domain": "a.com"}');
      expect(back!.domain, 'a.com');
      expect(back.bookNameSelector, isEmpty);
      expect(back.cleaningRulesText, isEmpty);
    });

    test('selector/whitespace 清洗无法行文本表达（一期已知限制）', () {
      final json = '''
        {
          "ruleId": "manual.a.com",
          "domain": "a.com",
          "version": 1,
          "contentCleaningRules": [
            {"type": "selector", "pattern": ".ad"},
            {"type": "regex", "pattern": "keep-me"}
          ]
        }
      ''';
      final back = decodeRuleDraft(json)!;
      expect(back.cleaningRulesText, 'keep-me');
    });
  });

  group('invalidArgumentFailure', () {
    test('fieldErrors 装配 INVALID_ARGUMENT + 明细', () {
      final failure = invalidArgumentFailure(<String, String>{
        'domain': '域名格式不正确',
        'version': '缺少必填字段',
      });
      // 注意：不能 isA<StateError>()——用错误码/明细断言；显式 import 后
      // 自定义 StateError 遮蔽 dart:core 同名类，可安全下转。
      final error = failure.error as StateError;
      expect(error.failure, 'INVALID_ARGUMENT');
      expect(error.debugDetail, contains('domain=域名格式不正确'));
      expect(error.debugDetail, contains('version=缺少必填字段'));
    });
  });
}
