// 문항 은행 MCP 서버 골격 (TypeScript SDK v2 · stdio)
//
// stdio 서버에서는 stdout 이 곧 프로토콜 채널이다.
// 로그는 console.error 만 쓴다(stdout 으로 찍는 로그 함수는 절대 쓰지 않는다).
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import { searchItems } from './itemApi.js';

const DEFAULT_LIMIT = 5;
const MAX_LIMIT = 20;

const server = new McpServer({ name: 'item-bank', version: '1.0.0' });

// 예시 도구: 서버가 살아 있는지 확인한다. 입력 없음.
server.registerTool(
  'ping',
  {
    description: '서버 연결 확인용 예시 도구. "pong" 을 돌려준다.'
  },
  async () => ({
    content: [{ type: 'text', text: 'pong' }]
  })
);

// ================================================================
// 여기에 도구를 등록합니다
//   server.registerTool('도구_이름', { description, inputSchema }, handler)
//
// - inputSchema 는 z.object({...}) 전체 스키마로 넘긴다 (import * as z from 'zod/v4')
// - 감쌀 API 는 ./itemApi.js 에 있다 (ESM 이라 확장자를 .js 로 적는다)
// - import 문도 이 자리에 함께 붙여 넣어도 된다
// ================================================================

server.registerTool(
  'search_items',
  {
    description:
      '문항 은행에서 문항을 검색한다. 사용자가 특정 주제·키워드의 문제, 단원별 문항, 난이도별 문항을 ' +
      '찾거나 추천해 달라고 할 때 사용한다. 키워드는 문항 본문과 태그에서 찾는다. ' +
      '읽기 전용이며 문항을 수정하지 않는다.',
    inputSchema: z.object({
      keyword: z.string().describe('검색어. 문항 본문 또는 태그에 포함된 말 (예: "분수", "서술형")'),
      unit: z.string().optional().describe('단원 코드(예: "M5-1") 또는 단원 이름 일부(예: "분수의 곱셈"). 생략하면 전체 단원'),
      difficulty: z.enum(['하', '중', '상']).optional().describe('난이도 "하" · "중" · "상". 생략하면 전체 난이도'),
      limit: z
        .number()
        .int()
        .min(1)
        .max(MAX_LIMIT)
        .default(DEFAULT_LIMIT)
        .describe(`돌려줄 최대 문항 수 (1~${MAX_LIMIT}, 기본 ${DEFAULT_LIMIT})`)
    })
  },
  async ({ keyword, unit, difficulty, limit }) => {
    console.error(`search_items called: keyword=${keyword} unit=${unit ?? '-'} difficulty=${difficulty ?? '-'} limit=${limit}`);
    const results = await searchItems({ keyword, unit, difficulty, limit });
    console.error(`search_items returned ${results.length} items`);

    if (results.length === 0) {
      const text = JSON.stringify({ message: '검색 결과 없음', condition: { keyword, unit, difficulty, limit } });
      return { content: [{ type: 'text', text }] };
    }
    return { content: [{ type: 'text', text: JSON.stringify(results) }] };
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('item-bank MCP server running on stdio');
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
