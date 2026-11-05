import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type CursorPage } from '@/shared/api/client';

export type ArticleStatus = 'draft' | 'review' | 'published' | 'review_due' | 'archived';
export type Audience = 'all' | 'employees' | 'agents' | 'group';
export type TemplateCode = 'blank' | 'howto' | 'solution' | 'faq' | 'known_error';

export const STATUSES: ArticleStatus[] = ['draft', 'review', 'published', 'review_due', 'archived'];
export const AUDIENCES: Audience[] = ['all', 'employees', 'agents', 'group'];

export interface Permissions {
  edit: boolean;
  submit: boolean;
  publish: boolean;
  reject: boolean;
  archive: boolean;
  unarchive: boolean;
  requestReview: boolean;
  confirmReview: boolean;
  viewHistory: boolean;
  restore: boolean;
}

export interface FeedbackSummary {
  helpful: number;
  notHelpful: number;
  mine: boolean | null;
}

export interface ArticleDetails {
  id: string;
  key: string;
  status: ArticleStatus;
  title: string;
  body: string;
  shownVersionNo: number;
  currentVersionNo: number | null;
  publishedVersionNo: number | null;
  categoryId: string | null;
  audience: Audience;
  audienceGroupId: string | null;
  ownerId: string | null;
  reviewerId: string | null;
  reviewComment: string | null;
  reviewAt: string | null;
  publishedAt: string | null;
  sourceTicketId: string | null;
  sourceTicketRef: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  fourEyes: boolean;
  feedback: FeedbackSummary;
  permissions: Permissions;
}

export interface ArticleListItem {
  id: string;
  key: string;
  title: string;
  snippet: string;
  status: ArticleStatus;
  audience: Audience;
  categoryId: string | null;
  ownerId: string | null;
  currentVersionNo: number;
  publishedVersionNo: number | null;
  publishedAt: string | null;
  reviewAt: string | null;
  updatedAt: string;
}

export interface ArticleSummary {
  id: string;
  key: string;
  title: string;
  snippet: string;
  status: ArticleStatus;
  audience: Audience;
  categoryId: string | null;
  publishedAt: string | null;
}

export interface VersionSummary {
  versionNo: number;
  title: string;
  notes: string;
  restoredFrom: number | null;
  published: boolean;
  current: boolean;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string;
  updatedBy: string | null;
}

export interface VersionDetails extends VersionSummary {
  body: string;
}

export interface Category {
  id: string;
  name: string;
  description: string;
  sortOrder: number;
  version: number;
}

export interface Template {
  code: TemplateCode;
  body: string;
}

export interface SecretFinding {
  kind: string;
  line: number;
}

export interface ListFilter {
  view: 'portal' | 'workspace';
  q?: string;
  status?: ArticleStatus;
  categoryId?: string;
  mine?: boolean;
  awaitingMyReview?: boolean;
}

const BASE = '/knowledge';
export const etag = (a: { version: number }) => `"${a.version}"`;

export const kbKeys = {
  all: ['knowledge'] as const,
  list: (f: ListFilter) => ['knowledge', 'list', f] as const,
  article: (id: string, current: boolean) => ['knowledge', 'article', id, current ? 'current' : 'published'] as const,
  versions: (id: string) => ['knowledge', 'versions', id] as const,
  version: (id: string, no: number) => ['knowledge', 'version', id, no] as const,
  categories: ['knowledge', 'categories'] as const,
  templates: ['knowledge', 'templates'] as const,
  suggestions: (text: string) => ['knowledge', 'suggestions', text] as const,
};

export function useArticleList(filter: ListFilter) {
  return useInfiniteQuery({
    queryKey: kbKeys.list(filter),
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<ArticleListItem>>(`${BASE}/articles`, {
        query: { ...filter, q: filter.q?.trim() || undefined, cursor: pageParam, limit: 30 },
        signal,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useArticle(id: string | undefined, current = false) {
  return useQuery({
    queryKey: kbKeys.article(id ?? '', current),
    queryFn: ({ signal }) =>
      api.get<ArticleDetails>(`${BASE}/articles/${id}`, { query: { version: current ? 'current' : undefined }, signal }),
    enabled: !!id,
  });
}

export function useVersions(id: string) {
  return useQuery({
    queryKey: kbKeys.versions(id),
    queryFn: ({ signal }) => api.get<VersionSummary[]>(`${BASE}/articles/${id}/versions`, { signal }),
  });
}

export function useVersion(id: string, no: number | null) {
  return useQuery({
    queryKey: kbKeys.version(id, no ?? 0),
    queryFn: ({ signal }) => api.get<VersionDetails>(`${BASE}/articles/${id}/versions/${no}`, { signal }),
    enabled: no != null,
    staleTime: Infinity,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: kbKeys.categories,
    queryFn: ({ signal }) => api.get<Category[]>(`${BASE}/categories`, { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useTemplates() {
  return useQuery({
    queryKey: kbKeys.templates,
    queryFn: ({ signal }) => api.get<Template[]>(`${BASE}/templates`, { signal }),
    staleTime: Infinity,
  });
}

/** Подсказки статей по тексту обращения (INC-05). Для формы заявки и поиска портала. */
export function useArticleSuggestions(text: string, limit = 5) {
  const trimmed = text.trim();
  return useQuery({
    queryKey: kbKeys.suggestions(trimmed),
    queryFn: ({ signal }) =>
      api.get<ArticleSummary[]>(`${BASE}/suggestions`, { query: { text: trimmed, limit }, signal }),
    enabled: trimmed.length >= 3,
    placeholderData: keepPreviousData,
  });
}

/** Новый ключ идемпотентности на каждое действие пользователя (API-04). */
export const newIdempotencyKey = () => crypto.randomUUID();

export interface CreateArticleInput {
  title: string;
  template?: TemplateCode;
  categoryId?: string | null;
  audience?: Audience;
  body?: string;
}

export interface CreateFromTicketInput {
  ticketId: string;
  ticketRef?: string;
  title: string;
  symptoms?: string;
  cause?: string;
  solution: string;
  categoryId?: string;
}

export interface UpdateArticleInput {
  title?: string;
  body?: string;
  categoryId?: string;
  clearCategory?: boolean;
  audience?: Audience;
  audienceGroupId?: string | null;
}

/** Сохраняет ответ сервера в кэш, чтобы следующий запрос шёл со свежим ETag. */
function useStoreArticle() {
  const qc = useQueryClient();
  return (a: ArticleDetails) => {
    qc.setQueryData(kbKeys.article(a.id, true), a);
    void qc.invalidateQueries({
      predicate: (q) =>
        q.queryKey[0] === 'knowledge' &&
        !(q.queryKey[1] === 'article' && q.queryKey[2] === a.id && q.queryKey[3] === 'current'),
    });
  };
}

export function useCreateArticle() {
  const store = useStoreArticle();
  return useMutation({
    mutationFn: ({ input, key }: { input: CreateArticleInput; key: string }) =>
      api.post<ArticleDetails>(`${BASE}/articles`, input, { idempotencyKey: key }),
    onSuccess: store,
  });
}

export function useCreateFromTicket() {
  const store = useStoreArticle();
  return useMutation({
    mutationFn: ({ input, key }: { input: CreateFromTicketInput; key: string }) =>
      api.post<ArticleDetails>(`${BASE}/articles/from-ticket`, input, { idempotencyKey: key }),
    onSuccess: store,
  });
}

export function useUpdateArticle() {
  const store = useStoreArticle();
  return useMutation({
    mutationFn: ({ article, input }: { article: { id: string; version: number }; input: UpdateArticleInput }) =>
      api.patch<ArticleDetails>(`${BASE}/articles/${article.id}`, input, { ifMatch: etag(article) }),
    onSuccess: store,
  });
}

export type ArticleAction =
  | 'submit'
  | 'publish'
  | 'reject'
  | 'archive'
  | 'unarchive'
  | 'request-review'
  | 'confirm-review';

export function useArticleAction() {
  const store = useStoreArticle();
  return useMutation({
    mutationFn: ({
      article,
      action,
      body,
    }: {
      article: { id: string; version: number };
      action: ArticleAction;
      body?: Record<string, unknown>;
    }) => api.post<ArticleDetails>(`${BASE}/articles/${article.id}/${action}`, body ?? {}, { ifMatch: etag(article) }),
    onSuccess: store,
  });
}

export function useRestoreVersion() {
  const store = useStoreArticle();
  return useMutation({
    mutationFn: ({ article, versionNo }: { article: { id: string; version: number }; versionNo: number }) =>
      api.post<ArticleDetails>(`${BASE}/articles/${article.id}/versions/${versionNo}/restore`, {}, {
        ifMatch: etag(article),
      }),
    onSuccess: store,
  });
}

export function useRateArticle(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { helpful: boolean; comment?: string }) =>
      api.put<FeedbackSummary>(`${BASE}/articles/${id}/feedback`, input),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['knowledge', 'article', id] }),
  });
}

export function useCreateCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, key }: { name: string; key: string }) =>
      api.post<Category>(`${BASE}/categories`, { name }, { idempotencyKey: key }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: kbKeys.categories }),
  });
}
