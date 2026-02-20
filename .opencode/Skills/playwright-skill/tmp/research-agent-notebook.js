/**
 * 访问Research Agent Notebook，查看实际代码示例
 */
const { chromium } = require('playwright');

const TARGET_URL = 'https://raw.githubusercontent.com/anthropics/claude-cookbooks/main/claude_agent_sdk/research_agent/agent.py';

(async () => {
  const browser = await chromium.launch({
    headless: false,
    slowMo: 50
  });

  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 }
  });

  const page = await context.newPage();

  console.log('正在访问Research Agent代码...');

  try {
    await page.goto(TARGET_URL, {
      waitUntil: 'networkidle',
      timeout: 30000
    });

    await page.waitForTimeout(2000);

    // 获取Python代码
    const pythonCode = await page.evaluate(() => {
      return document.body.innerText;
    });

    console.log('\n========================================');
    console.log('Research Agent 完整代码:');
    console.log('========================================\n');
    console.log(pythonCode);

    // 提取关键导入
    const imports = pythonCode.match(/^from .*? import.*$/gm);
    if (imports) {
      console.log('\n========================================');
      console.log('关键导入:');
      console.log('========================================\n');
      console.log([...new Set(imports)].join('\n'));
    }

    // 搜索agent相关的类和函数定义
    const definitions = pythonCode.match(/^(class |def |async def ).*?$/gm);
    if (definitions) {
      console.log('\n========================================');
      console.log('类和函数定义:');
      console.log('========================================\n');
      console.log(definitions.slice(0, 20).join('\n'));
    }

  } catch (error) {
    console.error('❌ 错误:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }

  console.log('\n✅ 代码获取完成');
})();
