package com.digitalpetri.opcua.stack.core.types.structured;

import com.digitalpetri.opcua.stack.core.serialization.DelegateRegistry;
import com.digitalpetri.opcua.stack.core.serialization.UaDecoder;
import com.digitalpetri.opcua.stack.core.serialization.UaEncoder;
import com.digitalpetri.opcua.stack.core.serialization.UaStructure;
import com.digitalpetri.opcua.stack.core.types.UaDataType;
import com.digitalpetri.opcua.stack.core.types.builtin.NodeId;
import com.digitalpetri.opcua.stack.core.Identifiers;

@UaDataType("BrowsePath")
public class BrowsePath implements UaStructure {

    public static final NodeId TypeId = Identifiers.BrowsePath;
    public static final NodeId BinaryEncodingId = Identifiers.BrowsePath_Encoding_DefaultBinary;
    public static final NodeId XmlEncodingId = Identifiers.BrowsePath_Encoding_DefaultXml;

    protected final NodeId _startingNode;
    protected final RelativePath _relativePath;

    public BrowsePath() {
        this._startingNode = null;
        this._relativePath = null;
    }

    public BrowsePath(NodeId _startingNode, RelativePath _relativePath) {
        this._startingNode = _startingNode;
        this._relativePath = _relativePath;
    }

    public NodeId getStartingNode() {
        return _startingNode;
    }

    public RelativePath getRelativePath() {
        return _relativePath;
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


    public static void encode(BrowsePath browsePath, UaEncoder encoder) {
        encoder.encodeNodeId("StartingNode", browsePath._startingNode);
        encoder.encodeSerializable("RelativePath", browsePath._relativePath != null ? browsePath._relativePath : new RelativePath());
    }

    public static BrowsePath decode(UaDecoder decoder) {
        NodeId _startingNode = decoder.decodeNodeId("StartingNode");
        RelativePath _relativePath = decoder.decodeSerializable("RelativePath", RelativePath.class);

        return new BrowsePath(_startingNode, _relativePath);
    }

    static {
        DelegateRegistry.registerEncoder(BrowsePath::encode, BrowsePath.class, BinaryEncodingId, XmlEncodingId);
        DelegateRegistry.registerDecoder(BrowsePath::decode, BrowsePath.class, BinaryEncodingId, XmlEncodingId);
    }

}
