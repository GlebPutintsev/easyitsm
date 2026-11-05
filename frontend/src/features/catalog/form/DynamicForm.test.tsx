import { fireEvent, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import '../i18n';
import { DynamicForm } from './DynamicForm';
import type { FormSchema, FormValues } from './types';

const schema: FormSchema = {
  fields: [
    { key: 'vpn', type: 'checkbox', label: 'Нужен VPN' },
    { key: 'reason', type: 'text', label: 'Для чего VPN', visibleWhen: { field: 'vpn', op: 'eq', value: true }, required: true },
  ],
};

function Harness() {
  const [values, setValues] = useState<FormValues>({});
  return <DynamicForm schema={schema} values={values} errors={{ reason: 'required' }} onChange={(k, v) => setValues((x) => ({ ...x, [k]: v }))} />;
}

describe('DynamicForm', () => {
  it('показывает поле по условию и ошибку у поля', () => {
    renderWithProviders(<Harness />);
    expect(screen.queryByLabelText(/Для чего VPN/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Нужен VPN'));
    expect(screen.getByLabelText(/Для чего VPN/)).toBeInTheDocument();
    expect(screen.getByText('Обязательное поле')).toBeInTheDocument();
  });
});
