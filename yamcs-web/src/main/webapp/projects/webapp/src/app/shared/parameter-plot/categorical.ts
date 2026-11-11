import { ParameterType } from '@yamcs/webapp-sdk';

export interface CategoricalValue {
  value: number;
  label: string;
}

/**
 * Value↔label table for a categorical (enum or boolean) parameter type, used
 * to render ordinals as labels on the Y-axis, legend and on-chart key.
 *
 * Enum ordinals are serialized as strings by the MDB API, so they are coerced.
 * Booleans get a synthetic 0/1 table from the type's zero/one string values.
 * Returns undefined for any other type.
 */
export function getCategoricalValues(type?: ParameterType): CategoricalValue[] | undefined {
  if (!type) {
    return undefined;
  }
  if (type.engType === 'enumeration' && type.enumValue?.length) {
    return type.enumValue.map(ev => ({ value: Number(ev.value), label: ev.label }));
  }
  if (type.engType === 'boolean') {
    return [
      { value: 0, label: type.zeroStringValue || 'FALSE' },
      { value: 1, label: type.oneStringValue || 'TRUE' },
    ];
  }
  return undefined;
}
