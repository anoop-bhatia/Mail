package in.anoopbhatia.mail;

import java.util.ArrayList;
import java.util.List;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

public class RingBuffer<T> {
    private final List<T> buffer;
    private int count = 0;
    private int out = 0;
    private int in = 0;

    public RingBuffer(int capacity) {
        if (capacity < 0)
            throw new NegativeArraySizeException();
        buffer = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++)
            buffer.add(null);
    }

    public synchronized void push(T item) {
        if (count == buffer.size())
            pop();

        buffer.set(in, item);
        in = (in + 1) % buffer.size();
        count++;
    }

    public synchronized T pop() {
        if (buffer.isEmpty())
            throw new ArrayIndexOutOfBoundsException(out);
        T item = buffer.get(out);
        buffer.set(out, null);
        count--;
        out = (out + 1) % buffer.size();
        return item;
    }

    public synchronized boolean isEmpty() {
        return (count == 0);
    }

    public synchronized int size() {
        return count;
    }
}
