/**
 * 访问Anthropic Cookbook，搜索agent相关示例
 */
const { chromium } = require('playwright');

const TARGET_URL = 'https://github.com/anthropics/anthropic-cookbook';

(async () => {
  const browser = await chromium.launch({
    headless: false,
    slowMo: 100
  });

  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 }
  });

  const page = await context.newPage();

  console.log('正在访问:', TARGET_URL);

  try {
    await page.goto(TARGET_URL, {
      waitUntil: 'networkidle',
      timeout: 30000
    });

    await page.waitForTimeout(2000);

    // 搜索agent相关内容
    const agentLinks = await page.evaluate(() => {
      const links = Array.from(document.querySelectorAll('a'));
      return links
        .filter(link => {
          const text = link.textContent || '';
          const href = link.href || '';
          return text.toLowerCase().includes('agent') ||
                 href.toLowerCase().includes('agent');
        })
        .map(link => ({
          text: link.textContent.trim(),
          href: link.href
        }))
        .slice(0, 15);
    });

    console.log('\n========================================');
    console.log('找到的Agent相关链接:');
    console.log('========================================\n');
    agentLinks.forEach((link, index) => {
      console.log(`${index + 1}. ${link.text}`);
      console.log(`   ${link.href}\n`);
    });

    // 获取README内容
    const readme = await page.evaluate(() => {
      const article = document.querySelector('article.markdown-body');
      return article ? article.innerText.substring(0, 2000) : null;
    });

    console.log('\n========================================');
    console.log('README内容预览:');
    console.log('========================================\n');
    console.log(readme);

    await page.screenshot({
      path: '/tmp/anthropic-cookbook.png',
      fullPage: true
    });

    console.log('\n📸 截图已保存到 /tmp/anthropic-cookbook.png');

  } catch (error) {
    console.error('❌ 错误:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }

  console.log('\n✅ 研究完成');
})();
