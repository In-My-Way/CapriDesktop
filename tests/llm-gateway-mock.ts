// The model is never called by these tests: only what she does with its answer
export const getLocalModel = (): string => 'test-model'
export const llmFetch = async (): Promise<Response> => new Response('{}', { status: 500 })
