package io.github.llm4j.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CalculatorToolTest {

    private final CalculatorTool calculator = new CalculatorTool();

    @Test
    void testSimpleAddition() throws Exception {
        String result = calculator.execute("2 + 2");
        assertThat(result).isEqualTo("4");
    }

    @Test
    void testMultiplication() throws Exception {
        String result = calculator.execute("15 * 23");
        assertThat(result).isEqualTo("345");
    }

    @Test
    void testComplexExpression() throws Exception {
        String result = calculator.execute("(10 + 5) * 2");
        assertThat(result).isEqualTo("30");
    }

    @Test
    void testDecimalNumbers() throws Exception {
        String result = calculator.execute("3.14 * 2");
        assertThat(result).isEqualTo("6.28");
    }

    @Test
    void testEmptyInput() throws Exception {
        String result = calculator.execute("");
        assertThat(result).contains("Error");
    }

    @Test
    void testToolMetadata() {
        assertThat(calculator.getName()).isEqualTo("Calculator");
        assertThat(calculator.getDescription()).isNotEmpty();
        assertThat(calculator.getDescription()).contains("mathematical");
    }
}
