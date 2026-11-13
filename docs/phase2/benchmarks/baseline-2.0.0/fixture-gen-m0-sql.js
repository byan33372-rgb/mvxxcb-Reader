// M0 基线夹具 SQL 生成（WP-Bench，基于 gen-perf-sql.js 思路扩展）
// 书 9527「性能压测·万章目录」：10000 章目录（无缓存正文）——目录滚动基准
// 书 9528「性能压测·长文阅读」：80 章全部缓存正文（每章 150 段 x 约 60 字）——阅读器滚动基准
// 书 9530..9569（40 本填充）：各 3 章缓存短正文——书架滚动基准（让书架列表有足够长度）
const fs = require('fs');
const now = Date.now();
const L = [];
// FK 级联开启：books REPLACE 时级联删除旧 chapters，保证幂等重注入；事务包裹提速
L.push('PRAGMA foreign_keys=ON;');
L.push('BEGIN TRANSACTION;');

// —— 书 9527：万章目录 ——
L.push(`INSERT OR REPLACE INTO books(id,title,author,coverUrl,description,sourceUrl,normalizedSourceUrl,sourceDomain,catalogUrl,latestChapterTitle,totalChapters,currentChapterId,lastReadAt,createdAt,updatedAt,isTemporary,sourceEncoding) VALUES(9527,'性能压测·万章目录',NULL,NULL,'M0 基线夹具·万章目录','https://perf.example.com/novel/9527.html','https://perf.example.com/novel/9527.html','perf.example.com','https://perf.example.com/novel/9527.html','第10000章 性能压测',10000,NULL,${now},${now},${now},0,NULL);`);
for (let i = 0; i < 10000; i++) {
  const title = `第${i + 1}章 性能压测`;
  const url = `https://perf.example.com/novel/9527/${i}.html`;
  L.push(`INSERT INTO chapters(bookId,chapterIndex,title,url,normalizedUrl,titleFingerprint,isCached,isMissing,wordCount) VALUES(9527,${i},'${title}','${url}','${url}','perf9527-${i}',0,0,0);`);
}

// —— 书 9528：长文阅读（全部缓存） ——
const READER_CH = 80, PARA = 150;
L.push(`INSERT OR REPLACE INTO books(id,title,author,coverUrl,description,sourceUrl,normalizedSourceUrl,sourceDomain,catalogUrl,latestChapterTitle,totalChapters,currentChapterId,lastReadAt,createdAt,updatedAt,isTemporary,sourceEncoding) VALUES(9528,'性能压测·长文阅读',NULL,NULL,'M0 基线夹具·长文阅读','https://perf.example.com/novel/9528.html','https://perf.example.com/novel/9528.html','perf.example.com','https://perf.example.com/novel/9528.html','第80章 性能压测',${READER_CH},NULL,${now - 1000},${now - 1000},${now - 1000},0,NULL);`);
for (let c = 0; c < READER_CH; c++) {
  const url = `https://perf.example.com/novel/9528/${c}.html`;
  const paras = [];
  for (let p = 0; p < PARA; p++) {
    paras.push(`万章压测正文·书9528·第${c + 1}章·段${p + 1}：这是一段用于性能基准测量的占位正文，长度约六十字，模拟小说阅读页的平均段落宽度与排版压力，内容无实际语义。`);
  }
  const json = JSON.stringify(paras.map(v => ({ t: 'p', v })));
  const wc = paras.reduce((s, v) => s + v.length, 0);
  L.push(`INSERT INTO chapters(bookId,chapterIndex,title,url,normalizedUrl,titleFingerprint,isCached,isMissing,wordCount,content,contentHash,fetchedAt) VALUES(9528,${c},'第${c + 1}章 性能压测','${url}','${url}','perf9528-${c}',1,0,${wc},'${json.replace(/'/g, "''")}',NULL,${now - 1000});`);
}

// —— 40 本填充书（9530..9569），供书架滚动 ——
for (let b = 0; b < 40; b++) {
  const id = 9530 + b;
  const title = `性能压测·书架填充${String(b + 1).padStart(2, '0')}`;
  L.push(`INSERT OR REPLACE INTO books(id,title,author,coverUrl,description,sourceUrl,normalizedSourceUrl,sourceDomain,catalogUrl,latestChapterTitle,totalChapters,currentChapterId,lastReadAt,createdAt,updatedAt,isTemporary,sourceEncoding) VALUES(${id},'${title}',NULL,NULL,'M0 基线夹具·书架填充','https://perf.example.com/novel/${id}.html','https://perf.example.com/novel/${id}.html','perf.example.com','https://perf.example.com/novel/${id}.html','第3章 性能压测',3,NULL,${now - 60000 - b},${now - 60000 - b},${now - 60000 - b},0,NULL);`);
  for (let c = 0; c < 3; c++) {
    const url = `https://perf.example.com/novel/${id}/${c}.html`;
    const paras = [];
    for (let p = 0; p < 10; p++) paras.push(`书架填充正文·书${id}·段${p + 1}：短占位段落。`);
    const json = JSON.stringify(paras.map(v => ({ t: 'p', v })));
    L.push(`INSERT INTO chapters(bookId,chapterIndex,title,url,normalizedUrl,titleFingerprint,isCached,isMissing,wordCount,content,fetchedAt) VALUES(${id},${c},'第${c + 1}章 性能压测','${url}','${url}','perf${id}-${c}',1,0,${paras.join('').length},'${json.replace(/'/g, "''")}',${now - 60000 - b});`);
  }
}

L.push('COMMIT;');
// 输出到脚本所在目录（可用第一个命令行参数覆盖），不假设任何机器路径
const path = require('path');
const outFile = process.argv[2] || path.join(__dirname, 'm0-perf.sql');
fs.writeFileSync(outFile, L.join('\n'));
console.log('OK 行数:', L.length, '→', outFile);
