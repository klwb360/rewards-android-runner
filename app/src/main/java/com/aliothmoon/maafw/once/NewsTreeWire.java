package com.aliothmoon.maafw.once;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Private in-memory pipe between the signed helper and the reading owner. Never written to disk. */
final class NewsTreeWire {
    static final int MAX_WIRE = 512 * 1024;
    private static final int MAGIC = 0x4e554931;
    record Tree(NativeUiStructureProtocol.Report source, List<NativeUiBoundPointsParser.Node> nodes) {}

    static void write(OutputStream sink, NativeUiStructureProtocol.Report source,
                      List<NativeUiBoundPointsParser.Node> nodes) throws IOException {
        DataOutputStream out = new DataOutputStream(sink);
        out.writeInt(MAGIC);
        out.writeUTF(NativeUiStructureProtocol.encode(source));
        out.writeInt(nodes.size());
        for (var node : nodes) {
            out.writeInt(node.index());
            out.writeInt(node.parentIndex());
            out.writeBoolean(node.visible());
            out.writeBoolean(node.packageMatches());
            out.writeUTF(node.resourceId() == null ? "" : node.resourceId());
            out.writeUTF(node.text());
            out.writeUTF(node.description());
            out.writeInt(node.left()); out.writeInt(node.top());
            out.writeInt(node.right()); out.writeInt(node.bottom());
            out.writeUTF(node.classRole().name());
            out.writeBoolean(node.clickable());
        }
        out.flush();
    }

    static Tree parse(byte[] bytes, int count, String nonce, long dispatch, long deadline, long now) throws IOException {
        if (bytes == null || count < 1 || count > MAX_WIRE || count > bytes.length) throw invalid();
        var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, count));
        if (input.readInt() != MAGIC) throw invalid();
        var source = NativeUiStructureProtocol.parse(input.readUTF(),nonce,dispatch,deadline,now,true,false,true);
        int size = input.readInt();
        if (size != (source.qualified() ? source.nodeCount() : 0) || size < 0 || size > 512) throw invalid();
        var nodes = new ArrayList<NativeUiBoundPointsParser.Node>(size);
        int chars = 0;
        for (int i = 0; i < size; i++) {
            int index = input.readInt(), parent = input.readInt();
            boolean visible = input.readBoolean(), own = input.readBoolean();
            String id = input.readUTF(), text = input.readUTF(), description = input.readUTF();
            int left=input.readInt(), top=input.readInt(), right=input.readInt(), bottom=input.readInt();
            var role=NativeUiBoundPointsParser.ClassRole.valueOf(input.readUTF());
            boolean clickable=input.readBoolean();
            if (!own || index != i || i == 0 && parent != -1 || i > 0 && (parent < 0 || parent >= i)
                    || id.length() > 2048 || text.length() > 2048 || description.length() > 2048
                    || visible && (left < source.left() || top < source.top() || right > source.right()
                    || bottom > source.bottom() || left >= right || top >= bottom)) throw invalid();
            chars += text.length()+description.length();
            nodes.add(new NativeUiBoundPointsParser.Node(index,parent,visible,true,id,text,description,
                    left,top,right,bottom,role,clickable));
        }
        if (input.read() != -1 || source.qualified() && chars != source.charCount()) throw invalid();
        return new Tree(source,List.copyOf(nodes));
    }

    private static IOException invalid() { return new IOException("Invalid bounded reading tree"); }
}
