import { Parameter, utils } from '@yamcs/webapp-sdk';
import { NamedParameterType } from './NamedParameterType';

export interface CategoricalValue {
  value: number;
  label: string;
}

/**
 * Value↔label table for a categorical (enum or boolean) parameter, used
 * to render ordinals as labels on the Y-axis, legend and on-chart key.
 *
 * For an aggregate or array member (parameter.path set), the type of that
 * member is used rather than the type of the containing parameter.
 *
 * Enum ordinals are serialized as strings by the MDB API, so they are coerced.
 * Booleans get a synthetic 0/1 table from the type's zero/one string values.
 * Returns undefined for any other type.
 */
export function getCategoricalValues(parameter?: NamedParameterType): CategoricalValue[] | undefined {
  if (!parameter?.type) {
    return undefined;
  }
  const type = utils.getParameterTypeForPath(parameter as Parameter);
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
