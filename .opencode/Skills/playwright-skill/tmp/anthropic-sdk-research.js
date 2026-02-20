/**
 * 访问Anthropic SDK Python仓库，提取关键信息
 */
const { chromium } = require('playwright');

const TARGET_URL = 'https://github.com/anthropics/anthropic-sdk-python';

(async () => {
  const browser = await chromium.launch({
    headless: false,
    slowMo: 100
  });

  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
  });

  const page = await context.newPage();

  console.log('正在访问:', TARGET_URL);

  try {
    await page.goto(TARGET_URL, {
      waitUntil: 'networkidle',
      timeout: 30000
    });

    // 等待页面加载
    await page.waitForTimeout(2000);

    // 提取README内容
    const readmeContent = await page.evaluate(() => {
      const readme = document.querySelector('article.markdown-body');
      if (readme) {
        // 获取前2000个字符
        return readme.innerText.substring(0, 3000);
      }
      return null;
    });

    console.log('\n========================================');
    console.log('README 内容:');
    console.log('========================================\n');
    console.log(readmeContent);

    // 查找agent相关内容
    const hasAgentInfo = await page.evaluate(() => {
      const text = document.body.innerText;
      const agentMentions = [];

      // 查找包含"agent"的段落
      const lines = text.split('\n');
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].toLowerCase().includes('agent') && i < lines.length - 2) {
          agentMentions.push(lines[i]);
          if (agentMentions.length >= 10) break;
        }
      }

      return agentMentions;
    });

    console.log('\n========================================');
    console.log('Agent 相关内容:');
    console.log('========================================\n');
    console.log(hasAgentInfo);

    // 截图保存
    await page.screenshot({
      path: '/tmp/anthropic-sdk-github.png',
      fullPage: true
    });
    console.log('\n📸 截图已保存到 /tmp/anthropic-sdk-github.png');

    // 查找代码示例
    const codeExamples = await page.evaluate(() => {
      const codeBlocks = document.querySelectorAll('pre code');
      return Array.from(codeBlocks).slice(0, 3).map(block => block.innerText);
    });

    console.log('\n========================================');
    console.log('代码示例:');
    console.log('========================================\n');
    codeExamples.forEach((example, index) => {
      console.log(`\n--- 示例 ${index + 1} ---\n`);
      console.log(example.substring(0, 500));
    });

  } catch (error) {
    console.error('❌ 错误:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }

  console.log('\n✅ 研究完成');
})();
