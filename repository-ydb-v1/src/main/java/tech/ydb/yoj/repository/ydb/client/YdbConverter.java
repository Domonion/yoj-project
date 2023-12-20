package tech.ydb.yoj.repository.ydb.client;

import com.google.protobuf.NullValue;
import com.google.protobuf.UnsafeByteOperations;
import com.yandex.ydb.ValueProtos;
import com.yandex.ydb.table.query.Params;
import com.yandex.ydb.table.result.ValueReader;
import com.yandex.ydb.table.values.DictType;
import com.yandex.ydb.table.values.ListType;
import com.yandex.ydb.table.values.OptionalType;
import com.yandex.ydb.table.values.PrimitiveType;
import com.yandex.ydb.table.values.StructType;
import com.yandex.ydb.table.values.TupleType;
import com.yandex.ydb.table.values.Type;
import com.yandex.ydb.table.values.Value;
import com.yandex.ydb.table.values.VoidType;
import com.yandex.ydb.table.values.VoidValue;
import com.yandex.ydb.table.values.proto.ProtoValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class YdbConverter {
    public static Value toSDK(ValueProtos.TypedValue typedValue) {
        return toSDK(typedValue.getType(), typedValue.getValue());
    }

    public static Value toSDK(ValueProtos.Type type, ValueProtos.Value value) {
        return ProtoValue.fromPb(typeToSDK(type), toValueSDK(type, value));
    }

    private static ValueProtos.Value toValueSDK(ValueProtos.Type type, ValueProtos.Value value) {
        if (type.hasListType()) {
            return convertProtoListValueToSDK(type, value.getItemsList());
        } else if (type.hasStructType()) {
            return convertProtoStructValueToSDK(type, value.getItemsList());
        } else if (type.hasTupleType()) {
            return convertProtoTupleValueToSDK(type, value.getItemsList());
        } else if (type.hasOptionalType()) {
            return convertOptionalProtoValueToSDK(type, value);
        } else if (type.hasDictType()) {
            return convertProtoDictValueToSDK(type, value);
        } else if (type.getTypeCase() == ValueProtos.Type.TypeCase.VOID_TYPE) {
            return VoidValue.of().toPb();
        } else {
            return convertProtoPrimitiveValueToSDK(type, value);
        }
    }

    private static Type typeToSDK(ValueProtos.Type type) {
        if (type.hasListType()) {
            return convertProtoListTypeToSDK(type);
        } else if (type.hasStructType()) {
            return convertProtoStructTypeToSDK(type);
        } else if (type.hasTupleType()) {
            return convertProtoTupleTypeToSDK(type);
        } else if (type.hasOptionalType()) {
            return convertOptionalProtoTypeToSDK(type);
        } else if (type.hasDictType()) {
            return convertProtoDictTypeToSDK(type);
        } else if (type.getTypeCase() == ValueProtos.Type.TypeCase.VOID_TYPE) {
            return VoidType.of();
        } else {
            return convertProtoPrimitiveTypeToSDK(type);
        }
    }

    private static PrimitiveType convertProtoPrimitiveTypeToSDK(ValueProtos.Type type) {
        switch (type.getTypeId()) {
            case JSON:
                return PrimitiveType.json();
            case JSON_DOCUMENT:
                return PrimitiveType.jsonDocument();
            case BOOL:
                return PrimitiveType.bool();
            case INT8:
                return PrimitiveType.int8();
            case UINT8:
                return PrimitiveType.uint8();
            case INT32:
                return PrimitiveType.int32();
            case UINT32:
                return PrimitiveType.uint32();
            case INT64:
                return PrimitiveType.int64();
            case UINT64:
                return PrimitiveType.uint64();
            case FLOAT:
                return PrimitiveType.float32();
            case DOUBLE:
                return PrimitiveType.float64();
            case STRING:
                return PrimitiveType.string();
            case UTF8:
                return PrimitiveType.utf8();
            case TIMESTAMP:
                return PrimitiveType.timestamp();
            case INTERVAL:
                return PrimitiveType.interval();
            default:
                throw new IllegalArgumentException(type.getTypeId().name());
        }
    }

    private static ValueProtos.Value convertProtoPrimitiveValueToSDK(ValueProtos.Type type, ValueProtos.Value value) {
        switch (type.getTypeId()) {
            case JSON:
                return ProtoValue.json(value.getTextValue());
            case JSON_DOCUMENT:
                return ProtoValue.jsonDocument(value.getTextValue());
            case BOOL:
                return ProtoValue.bool(value.getBoolValue());
            case INT8:
                return ProtoValue.int8((byte) value.getInt32Value());
            case UINT8:
                return ProtoValue.uint8((byte) value.getUint32Value());
            case INT32:
                return ProtoValue.int32(value.getInt32Value());
            case UINT32:
                return ProtoValue.uint32(value.getUint32Value());
            case INT64:
                return ProtoValue.int64(value.getInt64Value());
            case UINT64:
                return ProtoValue.uint64(value.getUint64Value());
            case FLOAT:
                return ProtoValue.float32(value.getFloatValue());
            case DOUBLE:
                return ProtoValue.float64(value.getDoubleValue());
            case STRING:
                if (value.getValueCase() == ValueProtos.Value.ValueCase.BYTES_VALUE) {
                    return ProtoValue.bytes(value.getBytesValue());
                } else {
                    return ProtoValue.string(value.getTextValue(), StandardCharsets.UTF_8);
                }
            case UTF8:
                return ProtoValue.text(value.getTextValue());
            case TIMESTAMP:
                return ProtoValue.timestamp(value.getUint64Value());
            case INTERVAL:
                return ProtoValue.interval(value.getInt64Value());
            default:
                throw new IllegalArgumentException(type.getTypeId() + ": " + value.toString());
        }
    }

    private static ValueProtos.Value convertProtoDictValueToSDK(ValueProtos.Type type, ValueProtos.Value value) {
        DictType dictType = convertProtoDictTypeToSDK(type);
        if (value.getPairsList().isEmpty()) {
            return dictType.emptyValue().toPb();
        } else {
            Map<Value, Value> values = value.getPairsList().stream()
                    .collect(toMap(
                            pair -> toSDK(type.getDictType().getKey(), pair.getKey()),
                            pair -> toSDK(type.getDictType().getPayload(), pair.getPayload())
                    ));
            return dictType.newValueOwn(values).toPb();
        }
    }

    private static DictType convertProtoDictTypeToSDK(ValueProtos.Type type) {
        return DictType.of(
                typeToSDK(type.getDictType().getKey()),
                typeToSDK(type.getDictType().getPayload())
        );
    }

    private static ValueProtos.Value convertOptionalProtoValueToSDK(ValueProtos.Type type, ValueProtos.Value value) {
        ValueProtos.Type itemType = type.getOptionalType().getItem();
        OptionalType optionalType = convertOptionalProtoTypeToSDK(type);
        return value.getValueCase() == ValueProtos.Value.ValueCase.NULL_FLAG_VALUE ?
                optionalType.emptyValue().toPb() :
                optionalType.newValue(toSDK(itemType, value)).toPb();
    }

    private static OptionalType convertOptionalProtoTypeToSDK(ValueProtos.Type type) {
        return OptionalType.of(typeToSDK(type.getOptionalType().getItem()));
    }

    private static ValueProtos.Value convertProtoStructValueToSDK(ValueProtos.Type type, List<ValueProtos.Value> items) {
        List<ValueProtos.StructMember> members = type.getStructType().getMembersList();
        Map<String, Value> values = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ValueProtos.StructMember member = members.get(i);
            values.put(member.getName(), toSDK(member.getType(), items.get(i)));
        }
        StructType structType = convertProtoStructTypeToSDK(type);
        return structType.newValue(values).toPb();
    }

    private static StructType convertProtoStructTypeToSDK(ValueProtos.Type type) {
        Map<String, Type> types = new LinkedHashMap<>();
        for (ValueProtos.StructMember member : type.getStructType().getMembersList()) {
            types.put(member.getName(), typeToSDK(member.getType()));
        }
        return StructType.of(types);
    }

    private static ValueProtos.Value convertProtoTupleValueToSDK(ValueProtos.Type type, List<ValueProtos.Value> items) {
        var members = type.getTupleType().getElementsList();
        var values = new ArrayList<Value>();

        for (int i = 0; i < items.size(); i++) {
            values.add(toSDK(members.get(i), items.get(i)));
        }

        return convertProtoTupleTypeToSDK(type).newValue(values).toPb();
    }

    private static TupleType convertProtoTupleTypeToSDK(ValueProtos.Type type) {
        return TupleType.of(
                type.getTupleType().getElementsList().stream().map(YdbConverter::typeToSDK).collect(toList())
        );
    }

    private static ValueProtos.Value convertProtoListValueToSDK(ValueProtos.Type type, List<ValueProtos.Value> items) {
        ListType listType = convertProtoListTypeToSDK(type);
        if (items.isEmpty()) {
            return listType.emptyValue().toPb();
        } else {
            List<Value> values = items.stream()
                    .map(v -> toSDK(type.getListType().getItem(), v))
                    .collect(toList());
            return listType.newValue(values).toPb();
        }
    }

    private static ListType convertProtoListTypeToSDK(ValueProtos.Type type) {
        return ListType.of(typeToSDK(type.getListType().getItem()));
    }

    public static ValueProtos.Value.Builder convertValueToProto(ValueReader column) {
        ValueProtos.Value.Builder builder = ValueProtos.Value.newBuilder();
        ValueProtos.Type type = column.getType().toPb();
        if (type.hasOptionalType()) {
            if (!column.isOptionalItemPresent()) {
                return builder.setNullFlagValue(NullValue.NULL_VALUE);
            }
            type = type.getOptionalType().getItem();
        }
        if (type.hasListType()) {
            for (int i = 0; i < column.getListItemsCount(); ++i) {
                builder.addItems(convertValueToProto(column.getListItem(i)));
            }
            return builder;
        } else if (type.hasDictType()) {
            for (int i = 0; i < column.getDictItemsCount(); ++i) {
                builder.addPairs(ValueProtos.ValuePair.newBuilder()
                        .setKey(convertValueToProto(column.getDictKey(i)))
                        .setPayload(convertValueToProto(column.getDictValue(i)))
                );
            }
            return builder;
        }
        switch (type.getTypeId()) {
            case JSON:
                return builder.setTextValue(column.getJson());
            case JSON_DOCUMENT:
                return builder.setTextValue(column.getJsonDocument());
            case BOOL:
                return builder.setBoolValue(column.getBool());
            case INT8:
            case INT16:
            case INT32:
                return builder.setInt32Value(column.getInt32());
            case UINT8:
                return builder.setUint32Value(column.getUint8());
            case UINT16:
                return builder.setUint32Value(column.getUint16());
            case UINT32:
                return builder.setUint32Value((int) column.getUint32());
            case INT64:
                return builder.setInt64Value(column.getInt64());
            case UINT64:
                return builder.setUint64Value(column.getUint64());
            case FLOAT:
                return builder.setFloatValue(column.getFloat32());
            case DOUBLE:
                return builder.setDoubleValue(column.getFloat64());
            case STRING:
                return builder.setBytesValue(UnsafeByteOperations.unsafeWrap(column.getString()));
            case UTF8:
                return builder.setTextValue(column.getUtf8());
            case TIMESTAMP:
                return builder.setUint64Value(column.getValue().toPb().getUint64Value());
            case INTERVAL:
                return builder.setInt64Value(column.getValue().toPb().getInt64Value());
            default:
                throw new IllegalArgumentException(column.getType().toPb().getTypeId() + ": " + column.toString());
        }
    }

    public static Params convertToParams(Map<String, ValueProtos.TypedValue> queryParameters) {
        if (queryParameters.isEmpty()) {
            return Params.empty();
        }

        @SuppressWarnings("unchecked")
        Map<String, Value<?>> values = queryParameters.entrySet().stream().collect(toMap(
                Map.Entry::getKey,
                o -> toSDK(o.getValue().getType(), o.getValue().getValue())
        ));
        return Params.copyOf(values);
    }
}
