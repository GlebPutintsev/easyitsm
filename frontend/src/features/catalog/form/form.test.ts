import { describe, expect, it } from 'vitest';
import { schemaProblems } from '../admin/FormBuilder';
import { resolveFields, testCondition } from './conditions';
import type { FormSchema } from './types';
import { validateForm } from './validate';

const schema: FormSchema = {
  fields: [
    { key: 'model', type: 'select', label: 'Модель', required: true, options: [{ value: 'basic', label: 'Базовый' }, { value: 'pro', label: 'Pro' }] },
    {
      key: 'reason',
      type: 'textarea',
      label: 'Зачем',
      min: 10,
      visibleWhen: { field: 'model', op: 'eq', value: 'pro' },
      requiredWhen: { field: 'model', op: 'eq', value: 'pro' },
    },
    { key: 'monitors', type: 'number', label: 'Мониторы', min: 0, max: 3 },
    { key: 'phone', type: 'text', label: 'Телефон', pattern: '\\+?[0-9]{10,12}' },
  ],
};

describe('условия формы', () => {
  it('совпадают с серверной логикой для чисел, списков и групп', () => {
    expect(testCondition({ field: 'a', op: 'eq', value: 5 }, { a: '5' })).toBe(true);
    expect(testCondition({ field: 'a', op: 'gt', value: 5 }, { a: 6 })).toBe(true);
    expect(testCondition({ field: 'a', op: 'in', value: ['x', 'y'] }, { a: 'y' })).toBe(true);
    expect(testCondition({ field: 'a', op: 'contains', value: 'mac' }, { a: ['win', 'mac'] })).toBe(true);
    expect(testCondition({ field: 'a', op: 'filled' }, { a: '  ' })).toBe(false);
    expect(testCondition({ any: [{ field: 'a', op: 'eq', value: 1 }, { field: 'b', op: 'filled' }] }, { b: 'x' })).toBe(true);
    expect(testCondition({ all: [{ field: 'a', op: 'eq', value: 1 }, { field: 'b', op: 'filled' }] }, { b: 'x' })).toBe(false);
  });

  it('показывают и делают обязательным поле по ответу выше', () => {
    expect(resolveFields(schema.fields, { model: 'basic' }).map((x) => x.field.key)).not.toContain('reason');
    const pro = resolveFields(schema.fields, { model: 'pro' });
    expect(pro.find((x) => x.field.key === 'reason')?.required).toBe(true);
  });
});

describe('проверка ответов', () => {
  it('возвращает те же коды ошибок, что сервер', () => {
    const { errors } = validateForm(schema, { model: 'pro', monitors: 5, phone: 'abc' });
    expect(errors).toEqual({ reason: 'required', monitors: 'max', phone: 'pattern' });
    expect(validateForm(schema, {}).errors).toEqual({ model: 'required' });
  });

  it('отбрасывает значения скрытых полей', () => {
    const r = validateForm(schema, { model: 'basic', reason: 'не нужно', monitors: '2' });
    expect(r.errors).toEqual({});
    expect(r.values).toEqual({ model: 'basic', monitors: 2 });
  });
});

describe('конструктор формы', () => {
  it('находит ошибки схемы до сохранения', () => {
    expect(schemaProblems(schema)).toEqual([]);
    expect(
      schemaProblems({
        fields: [
          { key: 'a', type: 'text', label: 'A', visibleWhen: { field: 'b', op: 'filled' } },
          { key: 'b', type: 'select', label: '', options: [] },
          { key: 'b', type: 'text', label: 'B2' },
          { key: 'Bad key', type: 'text', label: 'X' },
        ],
      }),
    ).toEqual(['condition:a', 'label:#2', 'options:b', 'duplicate:b', 'key:#4']);
  });
});
