package net.activitywatch.watchers.idea

class EventTest extends GroovyTestCase {

    void testEventConstructor(){
        EventData data = new EventData("Testfile.java", "aw-idea", "java")
        Event testable = new Event(data)
        assertEquals(testable.duration, 0);
        sleep(110);
        testable = new Event(data);
        assertTrue(testable.duration > 0.1);
    }
}
