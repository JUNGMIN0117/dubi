import 'package:ddubi/core/constants/layout_constants.dart';
import 'package:flutter/material.dart';

class ResponsiveContent extends StatelessWidget {
  const ResponsiveContent({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(
          maxWidth: LayoutConstants.maxContentWidth,
        ),
        child: Padding(
          padding: const EdgeInsets.all(LayoutConstants.pagePadding),
          child: child,
        ),
      ),
    );
  }
}
