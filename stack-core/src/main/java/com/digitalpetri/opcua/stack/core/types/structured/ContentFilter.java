package com.digitalpetri.opcua.stack.core.types.structured;

import com.digitalpetri.opcua.stack.core.serialization.DelegateRegistry;
import com.digitalpetri.opcua.stack.core.serialization.UaDecoder;
import com.digitalpetri.opcua.stack.core.serialization.UaEncoder;
import com.digitalpetri.opcua.stack.core.serialization.UaStructure;
import com.digitalpetri.opcua.stack.core.types.UaDataType;
import com.digitalpetri.opcua.stack.core.types.builtin.NodeId;
import com.digitalpetri.opcua.stack.core.Identifiers;

@UaDataType("ContentFilter")
public class ContentFilter implements UaStructure {

    public static final NodeId TypeId = Identifiers.ContentFilter;
    public static final NodeId BinaryEncodingId = Identifiers.ContentFilter_Encoding_DefaultBinary;
    public static final NodeId XmlEncodingId = Identifiers.ContentFilter_Encoding_DefaultXml;

    protected final ContentFilterElement[] _elements;

    public ContentFilter() {
        this._elements = null;
    }

    public ContentFilter(ContentFilterElement[] _elements) {
        this._elements = _elements;
    }

    public ContentFilterElement[] getElements() {
        return _elements;
    }

    @Override
    public NodeId getTypeId() {
        return TypeId;
    }

    @Override
    public NodeId getBinaryEncodingId() {
        return BinaryEncodingId;
    }

    @Override
    public NodeId getXmlEncodingId() {
        return XmlEncodingId;
    }


    public static void encode(ContentFilter contentFilter, UaEncoder encoder) {
        encoder.encodeArray("Elements", contentFilter._elements, encoder::encodeSerializable);
    }

    public static ContentFilter decode(UaDecoder decoder) {
        ContentFilterElement[] _elements = decoder.decodeArray("Elements", decoder::decodeSerializable, ContentFilterElement.class);

        return new ContentFilter(_elements);
    }

    static {
        DelegateRegistry.registerEncoder(ContentFilter::encode, ContentFilter.class, BinaryEncodingId, XmlEncodingId);
        DelegateRegistry.registerDecoder(ContentFilter::decode, ContentFilter.class, BinaryEncodingId, XmlEncodingId);
    }

}
