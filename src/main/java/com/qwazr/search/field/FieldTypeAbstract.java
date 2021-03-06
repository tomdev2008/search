/**
 * Copyright 2015-2016 Emmanuel Keller / QWAZR
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qwazr.search.field;

import com.qwazr.search.field.Converters.ValueConverter;
import com.qwazr.search.index.FieldConsumer;
import com.qwazr.search.index.FieldMap;
import com.qwazr.utils.SerializationUtils;
import com.qwazr.utils.StringUtils;
import com.qwazr.server.ServerException;
import jdk.nashorn.api.scripting.JSObject;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.BytesRef;

import javax.ws.rs.core.Response;
import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

abstract class FieldTypeAbstract implements FieldTypeInterface {

	final protected FieldMap.Item fieldMapItem;

	protected FieldTypeAbstract(final FieldMap.Item fieldMapItem) {
		this.fieldMapItem = fieldMapItem;
	}

	protected void fillArray(final String fieldName, final int[] values, final FieldConsumer consumer) {
		for (int value : values)
			fill(fieldName, value, consumer);
	}

	protected void fillArray(final String fieldName, final long[] values, final FieldConsumer consumer) {
		for (long value : values)
			fill(fieldName, value, consumer);
	}

	protected void fillArray(final String fieldName, final double[] values, final FieldConsumer consumer) {
		for (double value : values)
			fill(fieldName, value, consumer);
	}

	protected void fillArray(final String fieldName, final float[] values, final FieldConsumer consumer) {
		for (float value : values)
			fill(fieldName, value, consumer);
	}

	protected void fillArray(final String fieldName, final Object[] values, final FieldConsumer consumer) {
		for (Object value : values)
			fill(fieldName, value, consumer);
	}

	protected void fillArray(final String fieldName, final String[] values, final FieldConsumer consumer) {
		for (String value : values)
			fill(fieldName, value, consumer);
	}

	protected void fillCollection(final String fieldName, final Collection<Object> values,
			final FieldConsumer consumer) {
		values.forEach(value -> {
			if (value != null)
				fill(fieldName, value, consumer);
		});
	}

	protected void fillMap(final String fieldName, final Map<Object, Object> values, final FieldConsumer consumer) {
		throw new ServerException(Response.Status.NOT_ACCEPTABLE,
				"Map is not asupported type for the field: " + fieldName);
	}

	protected void fillJSObject(final String fieldName, final JSObject values, final FieldConsumer consumer) {
		fillCollection(fieldName, values.values(), consumer);
	}

	protected void fillDynamic(final Object value, final FieldConsumer fieldConsumer) {
		if (!(value instanceof Map))
			throw new ServerException(Response.Status.NOT_ACCEPTABLE,
					"Wrong value type for the field: " + fieldMapItem.name + ". A map was expected. We got a: "
							+ value.getClass());
		((Map<String, Object>) value).forEach((fieldName, valueObject) -> {
			if (!fieldMapItem.match(fieldName))
				throw new ServerException(Response.Status.NOT_ACCEPTABLE,
						"The field name does not match the field pattern: " + fieldMapItem.name);
			fill(fieldName, valueObject, fieldConsumer);
		});
	}

	protected void fill(final String fieldName, final Object value, final FieldConsumer fieldConsumer) {
		if (value == null)
			return;
		if (value instanceof String[])
			fillArray(fieldName, (String[]) value, fieldConsumer);
		else if (value instanceof int[])
			fillArray(fieldName, (int[]) value, fieldConsumer);
		else if (value instanceof long[])
			fillArray(fieldName, (long[]) value, fieldConsumer);
		else if (value instanceof double[])
			fillArray(fieldName, (double[]) value, fieldConsumer);
		else if (value instanceof float[])
			fillArray(fieldName, (float[]) value, fieldConsumer);
		else if (value instanceof Object[])
			fillArray(fieldName, (Object[]) value, fieldConsumer);
		else if (value instanceof Collection)
			fillCollection(fieldName, (Collection) value, fieldConsumer);
		else if (value instanceof JSObject)
			fillJSObject(fieldName, (JSObject) value, fieldConsumer);
		else if (value instanceof Map)
			fillMap(fieldName, (Map) value, fieldConsumer);
		else
			fillValue(fieldName, value, fieldConsumer);
	}

	abstract protected void fillValue(final String fieldName, final Object value, final FieldConsumer fieldConsumer);

	@Override
	final public void dispatch(final String fieldName, final Object value, final FieldConsumer fieldConsumer) {
		if (value == null)
			return;
		if (fieldMapItem.matcher != null && fieldMapItem.name.equals(fieldName))
			fillDynamic(value, fieldConsumer);
		else
			fill(fieldName, value, fieldConsumer);
	}

	@Override
	public Object toTerm(final BytesRef bytesRef) {
		return bytesRef == null ? null : bytesRef.utf8ToString();
	}

	@Override
	public ValueConverter getConverter(final String fieldName, final IndexReader reader) throws IOException {
		return ValueConverter.newConverter(fieldName, fieldMapItem.definition, reader);
	}

	final protected byte[] toBytes(final String fieldName, final Serializable value) {
		try {
			return SerializationUtils.toExternalizorBytes(value);
		} catch (IOException | ReflectiveOperationException e) {
			throw new ServerException(Response.Status.NOT_ACCEPTABLE,
					"Cannot serialize the value of the field " + fieldName, e);
		}
	}

	final protected byte[] toBytes(final String fieldName, final Externalizable value) {
		try {
			return SerializationUtils.toExternalizorBytes(value);
		} catch (IOException | ReflectiveOperationException e) {
			throw new ServerException(Response.Status.NOT_ACCEPTABLE,
					"Cannot serialize the value of the field " + fieldName, e);
		}
	}

	final protected <T> T notNull(final T value, final String fieldName, final String msg) {
		if (value != null)
			return value;
		throw new RuntimeException("Error on field: " + fieldName + " - " + msg == null ? StringUtils.EMPTY : msg);
	}

}
