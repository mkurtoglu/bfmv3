package com.bisoft.bfm.common.json;

/** A value that knows how to turn itself into plain JSON values (maps, lists, strings, numbers). */
public interface JsonWritable {
    Object toJsonValue();

    default String toJson() {
        return Json.write(this);
    }
}
