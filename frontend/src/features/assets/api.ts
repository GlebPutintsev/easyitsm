import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';

/** ИТ-активы (docs/api/assets-contract.md): оборудование, лицензии, договоры, поставщики, местоположения. */

export type AssetState = 'in_stock' | 'in_use' | 'in_repair' | 'retired';
export type AssetKind =
  | 'laptop'
  | 'desktop'
  | 'monitor'
  | 'server'
  | 'network'
  | 'phone'
  | 'tablet'
  | 'printer'
  | 'peripheral'
  | 'other';
export type ContractKind = 'purchase' | 'lease' | 'support' | 'license' | 'service' | 'other';
export type ContractStatus = 'active' | 'expiring' | 'expired' | 'terminated';
export type LicenseMetric = 'device' | 'user' | 'concurrent' | 'site' | 'subscription';
export type LicenseStatus = 'active' | 'expiring' | 'expired';

export const ASSET_STATES: AssetState[] = ['in_stock', 'in_use', 'in_repair', 'retired'];
export const ASSET_KINDS: AssetKind[] = [
  'laptop',
  'desktop',
  'monitor',
  'server',
  'network',
  'phone',
  'tablet',
  'printer',
  'peripheral',
  'other',
];
export const CONTRACT_KINDS: ContractKind[] = ['purchase', 'lease', 'support', 'license', 'service', 'other'];
export const LICENSE_METRICS: LicenseMetric[] = ['device', 'user', 'concurrent', 'site', 'subscription'];

export interface Named {
  id: string;
  name: string | null;
}

export interface ContractRef {
  id: string;
  number: string;
  title: string;
}

export interface CiInfo {
  id: string;
  name: string | null;
  classCode: string | null;
  className: string | null;
  status: string | null;
}

export interface AssetRow {
  id: string;
  tag: string;
  name: string;
  kind: AssetKind;
  model: string | null;
  serial: string | null;
  state: AssetState;
  holder: Named | null;
  location: Named | null;
  warrantyTo: string | null;
  cost: number | null;
  currency: string;
}

export interface Asset {
  id: string;
  tag: string;
  name: string;
  kind: AssetKind;
  manufacturer: string | null;
  model: string | null;
  serial: string | null;
  state: AssetState;
  holder: Named | null;
  location: Named | null;
  ci: CiInfo | null;
  vendor: Named | null;
  contract: ContractRef | null;
  purchasedOn: string | null;
  warrantyTo: string | null;
  warrantyExpired: boolean;
  serviceLifeMonths: number | null;
  endOfLife: string | null;
  endOfLifeReached: boolean;
  cost: number | null;
  currency: string;
  notes: string;
  retireReason: string | null;
  retiredAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface AssetFields {
  tag?: string;
  name?: string;
  kind?: AssetKind;
  manufacturer?: string;
  model?: string;
  serial?: string;
  locationId?: string;
  ciId?: string;
  vendorId?: string;
  contractId?: string;
  purchasedOn?: string;
  warrantyTo?: string;
  serviceLifeMonths?: number;
  cost?: number;
  currency?: string;
  notes?: string;
  clear?: string[];
}

export interface TicketInfo {
  id: string;
  number: string;
  title: string;
  statusCategory: string;
}

export type AssetEventKind =
  | 'created'
  | 'updated'
  | 'issued'
  | 'returned'
  | 'transferred'
  | 'repair'
  | 'retired'
  | 'ticket_linked'
  | 'ticket_unlinked';

export interface AssetEvent {
  id: string;
  kind: AssetEventKind;
  fromState: AssetState | null;
  toState: AssetState | null;
  fromHolder: Named | null;
  toHolder: Named | null;
  ticket: TicketInfo | null;
  comment: string | null;
  actor: Named | null;
  at: string;
}

export interface AssetStats {
  total: number;
  inStock: number;
  inUse: number;
  inRepair: number;
  retired: number;
  warrantyExpiring: number;
  endOfLife: number;
  cost: { currency: string; amount: number }[];
}

export interface Location {
  id: string;
  name: string;
  address: string | null;
  active: boolean;
  version: number;
}

export interface Vendor {
  id: string;
  name: string;
  taxId: string | null;
  contactName: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  notes: string;
  active: boolean;
  version: number;
}

export type VendorFields = Partial<Omit<Vendor, 'id' | 'version'>>;

export interface Contract {
  id: string;
  number: string;
  title: string;
  kind: ContractKind;
  vendor: Named | null;
  startsOn: string;
  endsOn: string | null;
  daysLeft: number | null;
  renewalNoticeDays: number;
  autoRenew: boolean;
  amount: number | null;
  currency: string;
  owner: Named | null;
  notes: string;
  terminated: boolean;
  status: ContractStatus;
  remindedFor: string | null;
  assets: number;
  licenses: number;
  version: number;
}

export interface ContractFields {
  number?: string;
  title?: string;
  kind?: ContractKind;
  vendorId?: string;
  startsOn?: string;
  endsOn?: string;
  renewalNoticeDays?: number;
  autoRenew?: boolean;
  amount?: number;
  currency?: string;
  ownerId?: string;
  notes?: string;
  terminated?: boolean;
  clear?: string[];
}

export interface License {
  id: string;
  product: string;
  publisher: string | null;
  metric: LicenseMetric;
  quantity: number | null;
  used: number;
  available: number | null;
  overused: boolean;
  validFrom: string | null;
  validTo: string | null;
  daysLeft: number | null;
  renewalNoticeDays: number;
  status: LicenseStatus;
  vendor: Named | null;
  contract: ContractRef | null;
  cost: number | null;
  currency: string;
  owner: Named | null;
  notes: string;
  version: number;
}

export interface LicenseFields {
  product?: string;
  publisher?: string;
  metric?: LicenseMetric;
  quantity?: number;
  validFrom?: string;
  validTo?: string;
  renewalNoticeDays?: number;
  vendorId?: string;
  contractId?: string;
  cost?: number;
  currency?: string;
  ownerId?: string;
  notes?: string;
  clear?: string[];
}

export interface Assignment {
  id: string;
  licenseId: string;
  product: string | null;
  asset: Named | null;
  user: Named | null;
  assignedBy: Named | null;
  assignedAt: string;
}

export interface AssetFilter {
  q?: string;
  state?: AssetState;
  kind?: AssetKind;
  holderId?: string;
  locationId?: string;
  ticketId?: string;
  contractId?: string;
}

export const assetKeys = {
  all: ['assets'] as const,
  list: (f: AssetFilter) => ['assets', 'list', f] as const,
  stats: ['assets', 'stats'] as const,
  one: (id: string) => ['assets', 'one', id] as const,
  history: (id: string) => ['assets', 'history', id] as const,
  tickets: (id: string) => ['assets', 'tickets', id] as const,
  licensesOf: (id: string) => ['assets', 'licenses-of', id] as const,
  locations: ['assets', 'locations'] as const,
  vendors: ['assets', 'vendors'] as const,
  contracts: (q?: string, status?: string) => ['assets', 'contracts', q ?? '', status ?? ''] as const,
  contract: (id: string) => ['assets', 'contract', id] as const,
  licenses: (q?: string, status?: string) => ['assets', 'licenses', q ?? '', status ?? ''] as const,
  license: (id: string) => ['assets', 'license', id] as const,
  assignments: (id: string) => ['assets', 'assignments', id] as const,
  mine: ['assets', 'mine'] as const,
};

// ------------------------------------------------------------------ оборудование

export function useAssets(filter: AssetFilter) {
  return useInfiniteQuery({
    queryKey: assetKeys.list(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<AssetRow>>('/assets', {
        query: { ...filter, q: filter.q || undefined, cursor: pageParam, limit: 50 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useAssetStats() {
  return useQuery({
    queryKey: assetKeys.stats,
    queryFn: ({ signal }) => api.get<AssetStats>('/assets/stats', { signal }),
  });
}

export function useAsset(id: string | undefined) {
  return useQuery({
    queryKey: assetKeys.one(id ?? ''),
    queryFn: ({ signal }) => api.get<Asset>(`/assets/${id}`, { signal }),
    enabled: !!id,
  });
}

export function useAssetHistory(id: string) {
  return useQuery({
    queryKey: assetKeys.history(id),
    queryFn: ({ signal }) => api.get<AssetEvent[]>(`/assets/${id}/history`, { signal }),
  });
}

export function useAssetTickets(id: string) {
  return useQuery({
    queryKey: assetKeys.tickets(id),
    queryFn: ({ signal }) => api.get<TicketInfo[]>(`/assets/${id}/tickets`, { signal }),
  });
}

export function useAssetLicenses(id: string) {
  return useQuery({
    queryKey: assetKeys.licensesOf(id),
    queryFn: ({ signal }) => api.get<Assignment[]>(`/assets/${id}/licenses`, { signal }),
  });
}

/** Активы, связанные с заявкой: правая панель карточки заявки. */
export function useTicketAssets(ticketId: string | undefined) {
  return useQuery({
    queryKey: assetKeys.list({ ticketId }),
    queryFn: ({ signal }) =>
      api.get<CursorPage<AssetRow>>('/assets', { query: { ticketId, limit: 50 }, signal }).then((p) => p.items),
    enabled: !!ticketId,
  });
}

export function useMyAssets() {
  return useQuery({
    queryKey: assetKeys.mine,
    queryFn: async ({ signal }) => {
      const [assets, licenses] = await Promise.all([
        api.get<AssetRow[]>('/me/assets', { signal }),
        api.get<Assignment[]>('/me/licenses', { signal }),
      ]);
      return { assets, licenses };
    },
  });
}

export function useCreateAsset() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fields: AssetFields) =>
      api.post<Asset>('/assets', fields, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

export function useUpdateAsset(asset: Asset) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fields: AssetFields) =>
      api.patch<Asset>(`/assets/${asset.id}`, fields, { ifMatch: etagOf(asset.version) }),
    onSuccess: (saved) => {
      qc.setQueryData(assetKeys.one(saved.id), saved);
      void qc.invalidateQueries({ queryKey: assetKeys.all });
    },
  });
}

export type AssetAction = 'issue' | 'transfer' | 'return' | 'repair' | 'retire';

export interface ActionBody {
  holderId?: string;
  locationId?: string;
  comment?: string;
  reason?: string;
}

export function useAssetAction(asset: Asset) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ action, body }: { action: AssetAction; body: ActionBody }) =>
      api.post<Asset>(`/assets/${asset.id}/${action}`, body, { ifMatch: etagOf(asset.version) }),
    onSuccess: (saved) => {
      qc.setQueryData(assetKeys.one(saved.id), saved);
      void qc.invalidateQueries({ queryKey: assetKeys.all });
    },
  });
}

export function useLinkTicket(assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { ticketId?: string; number?: string }) =>
      api.post<TicketInfo[]>(`/assets/${assetId}/tickets`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

export function useUnlinkTicket(assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ticketId: string) => api.delete(`/assets/${assetId}/tickets/${ticketId}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

// ------------------------------------------------------------------ справочники

export function useLocations() {
  return useQuery({
    queryKey: assetKeys.locations,
    queryFn: ({ signal }) => api.get<Location[]>('/asset-locations', { signal }),
    staleTime: 60_000,
  });
}

export function useSaveLocation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ location, fields }: { location?: Location; fields: Partial<Omit<Location, 'id' | 'version'>> }) =>
      location
        ? api.patch<Location>(`/asset-locations/${location.id}`, fields, { ifMatch: etagOf(location.version) })
        : api.post<Location>('/asset-locations', fields, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.locations }),
  });
}

export function useVendors() {
  return useQuery({
    queryKey: assetKeys.vendors,
    queryFn: ({ signal }) => api.get<Vendor[]>('/vendors', { signal }),
    staleTime: 60_000,
  });
}

export function useSaveVendor() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ vendor, fields }: { vendor?: Vendor; fields: VendorFields }) =>
      vendor
        ? api.patch<Vendor>(`/vendors/${vendor.id}`, fields, { ifMatch: etagOf(vendor.version) })
        : api.post<Vendor>('/vendors', fields, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

// ------------------------------------------------------------------ договоры

export function useContracts(q?: string, status?: ContractStatus) {
  return useQuery({
    queryKey: assetKeys.contracts(q, status),
    queryFn: ({ signal }) => api.get<Contract[]>('/contracts', { query: { q: q || undefined, status }, signal }),
    placeholderData: keepPreviousData,
  });
}

export function useContract(id: string | undefined) {
  return useQuery({
    queryKey: assetKeys.contract(id ?? ''),
    queryFn: ({ signal }) => api.get<Contract>(`/contracts/${id}`, { signal }),
    enabled: !!id,
  });
}

export function useSaveContract() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ contract, fields }: { contract?: Contract; fields: ContractFields }) =>
      contract
        ? api.patch<Contract>(`/contracts/${contract.id}`, fields, { ifMatch: etagOf(contract.version) })
        : api.post<Contract>('/contracts', fields, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

// ------------------------------------------------------------------ лицензии

export function useLicenses(q?: string, status?: LicenseStatus | 'overused') {
  return useQuery({
    queryKey: assetKeys.licenses(q, status),
    queryFn: ({ signal }) => api.get<License[]>('/licenses', { query: { q: q || undefined, status }, signal }),
    placeholderData: keepPreviousData,
  });
}

export function useLicense(id: string | undefined) {
  return useQuery({
    queryKey: assetKeys.license(id ?? ''),
    queryFn: ({ signal }) => api.get<License>(`/licenses/${id}`, { signal }),
    enabled: !!id,
  });
}

export function useSaveLicense() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ license, fields }: { license?: License; fields: LicenseFields }) =>
      license
        ? api.patch<License>(`/licenses/${license.id}`, fields, { ifMatch: etagOf(license.version) })
        : api.post<License>('/licenses', fields, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

export function useAssignments(licenseId: string) {
  return useQuery({
    queryKey: assetKeys.assignments(licenseId),
    queryFn: ({ signal }) => api.get<Assignment[]>(`/licenses/${licenseId}/assignments`, { signal }),
  });
}

export function useAssign(licenseId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { assetId?: string; userId?: string }) =>
      api.post<Assignment>(`/licenses/${licenseId}/assignments`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}

export function useUnassign(licenseId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (assignmentId: string) => api.delete(`/licenses/${licenseId}/assignments/${assignmentId}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: assetKeys.all }),
  });
}
