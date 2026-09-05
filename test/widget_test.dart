import 'package:flutter_test/flutter_test.dart';
import 'package:uncry/main.dart';

void main() {
  testWidgets('renders the minimal Uncry screen', (WidgetTester tester) async {
    await tester.pumpWidget(const UncryApp());

    expect(find.text('Uncry'), findsOneWidget);
  });
}
